"""Settings: everything lives in .env, re-read whenever the file changes.

- `get(name)` / `flag(name)` read one raw value (the file wins over real environment variables).
- `FIELDS` describes every user-facing option once: its default, how to read it leniently,
  and how to validate it strictly when the settings page saves it.
- `save({...})` writes values back into .env, keeping comments and the other lines intact.
- `shared_state()` is a small JSON store for things every server process must agree on.

Safe with several server processes (e.g. gunicorn -w 4): every write happens under a
cross-process file lock and replaces the file atomically, so nobody ever reads half a file.
"""
import contextlib
import hashlib
import hmac
import json
import os
import re
import logging
import secrets
import stat
import tempfile
import threading
import time
import uuid
from urllib.parse import urlparse

from dotenv import dotenv_values

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_PATH = os.path.join(BASE_DIR, ".env")
EXAMPLE_PATH = os.path.join(BASE_DIR, ".env.example")
LOCK_PATH = os.path.join(BASE_DIR, ".showcase.lock")
STATE_PATH = os.path.join(BASE_DIR, ".showcase-state.json")

ANIMATIONS = ["zoomIn", "zoomOut", "moveRight", "moveLeft", "moveUp", "moveDown", "goGray", "goColor"]
POSITIONS = ("left", "center", "right")

_lock = threading.RLock()
_held = threading.local()
_cache = {"stamp": None, "values": {}}


# --------------------------------------------------------------------------- locking & atomic writes
@contextlib.contextmanager
def file_lock():
    """Exclusive lock shared by every thread and every process using this folder. Re-entrant per thread."""
    if getattr(_held, "depth", 0):
        _held.depth += 1
        try:
            yield
        finally:
            _held.depth -= 1
        return
    with _lock:
        fd = os.open(LOCK_PATH, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            if os.name == "nt":
                import msvcrt
                while True:
                    try:
                        msvcrt.locking(fd, msvcrt.LK_LOCK, 1)
                        break
                    except OSError:
                        continue                     # LK_LOCK gives up after ~10s; keep waiting
            else:
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_EX)
            _held.depth = 1
            try:
                yield
            finally:
                _held.depth = 0
                if os.name == "nt":
                    with contextlib.suppress(OSError):
                        os.lseek(fd, 0, 0)
                        msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
        finally:
            os.close(fd)                             # also releases flock


def _atomic_write(path, text):
    """Write a new file next to `path`, copy its owner/permissions, then swap it in."""
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".tmp-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        try:
            st = os.stat(path)
            os.chmod(tmp, stat.S_IMODE(st.st_mode))
            if hasattr(os, "chown"):
                with contextlib.suppress(PermissionError):
                    os.chown(tmp, st.st_uid, st.st_gid)
        except FileNotFoundError:
            os.chmod(tmp, 0o600)                     # new files hold secrets: owner-only
        for attempt in range(40):
            try:
                os.replace(tmp, path)
                break
            except PermissionError:
                # Windows refuses to replace a file another process is reading at that instant. Linux never does.
                if os.name != "nt" or attempt == 39:
                    raise
                time.sleep(0.05)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise


# --------------------------------------------------------------------------- raw access
def _stamp():
    try:
        st = os.stat(ENV_PATH)
        return (st.st_mtime_ns, st.st_size, st.st_ino)
    except FileNotFoundError:
        return None


def all_values():
    with _lock:
        stamp = _stamp()
        if stamp != _cache["stamp"]:
            values = {}
            if stamp:
                try:
                    raw = dotenv_values(ENV_PATH, interpolate=False)
                    values = {k: v for k, v in raw.items() if v is not None}
                except OSError as e:
                    logging.getLogger("showcase").error(
                        "Can't read %s (%s) - check its owner/permissions match the user running Showcase Web",
                        ENV_PATH, e)
                    return {**os.environ, **_cache["values"]}     # keep the last good settings
            _cache.update(stamp=stamp, values=values)
        return {**os.environ, **_cache["values"]}


def get(name, default=""):
    v = all_values().get(name)
    v = v.strip() if v else ""
    return v or default


def flag(name, default=False):
    v = get(name).lower()
    return default if not v else v in ("1", "true", "yes", "on")


def ensure_file():
    """Create .env from the example (comments and all) on first run."""
    with file_lock():
        if not os.path.exists(ENV_PATH):
            text = ""
            if os.path.exists(EXAMPLE_PATH):
                with open(EXAMPLE_PATH, encoding="utf-8", newline="") as f:
                    text = f.read()
            _atomic_write(ENV_PATH, text)            # owner-only permissions: it will hold your token


def _quote(value):
    # Single quotes: no variable expansion; python-dotenv un-escapes \\ and \' inside them.
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def save(updates):
    """Write {NAME: string} into .env in one atomic replace. Comments and other lines are kept."""
    updates = {name: str(value) for name, value in updates.items()}
    for name, value in updates.items():
        if "\n" in value or "\r" in value:
            raise ValueError(f"{name}: must be a single line")
    with file_lock():
        ensure_file()
        with open(ENV_PATH, encoding="utf-8", newline="") as f:
            lines = f.read().splitlines(keepends=True)
        patterns = {n: re.compile(rf"^\s*(?:export\s+)?{re.escape(n)}\s*=") for n in updates}
        written = set()
        for i, line in enumerate(lines):
            for name, pattern in patterns.items():
                if pattern.match(line):
                    ending = "\r\n" if line.endswith("\r\n") else "\n"
                    lines[i] = f"{name}={_quote(updates[name])}{ending}"
                    written.add(name)
                    break
        missing = [n for n in updates if n not in written]
        if missing:
            if lines and not lines[-1].endswith("\n"):
                lines[-1] += "\n"
            lines += [f"{n}={_quote(updates[n])}\n" for n in missing]
        _atomic_write(ENV_PATH, "".join(lines))
        _cache["stamp"] = None


def init_secrets():
    """Generate the per-install IDs/keys once. Safe when several processes start at the same moment."""
    with file_lock():
        ensure_file()
        _cache["stamp"] = None                       # re-read: another process may have just written them
        updates = {}
        if not get("PLEX_CLIENT_ID"):
            updates["PLEX_CLIENT_ID"] = str(uuid.uuid4())
        if not get("SECRET_KEY"):
            updates["SECRET_KEY"] = secrets.token_hex(32)
        if updates:
            save(updates)


def secret_key():
    """The session signing key - read from .env every time, so all processes always agree."""
    key = get("SECRET_KEY")
    if not key:
        init_secrets()
        key = get("SECRET_KEY")
    return key


# --------------------------------------------------------------------------- shared state
@contextlib.contextmanager
def shared_state():
    """Small JSON store every server process sees (sign-ins in progress, login lockouts, setup code).
    Hold it briefly: don't make network calls inside."""
    with file_lock():
        try:
            with open(STATE_PATH, encoding="utf-8") as f:
                data = json.load(f)
        except (FileNotFoundError, ValueError):
            data = {}
        before = json.dumps(data, sort_keys=True)
        yield data
        if json.dumps(data, sort_keys=True) != before:
            _atomic_write(STATE_PATH, json.dumps(data))


# --------------------------------------------------------------------------- admin password
def hash_password(password):
    salt = secrets.token_bytes(16)
    n, r, p = 2 ** 14, 8, 1
    dk = hashlib.scrypt(password.encode(), salt=salt, n=n, r=r, p=p, dklen=32)
    return f"scrypt:{n}:{r}:{p}:{salt.hex()}:{dk.hex()}"


def check_password(password, stored):
    try:
        algo, n, r, p, salt, dk = stored.split(":")
        if algo != "scrypt":
            return False
        expected = bytes.fromhex(dk)
        actual = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt),
                                n=int(n), r=int(r), p=int(p), dklen=len(expected))
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False


def password_fingerprint():
    """Changes whenever the admin password changes - used to end old login sessions."""
    stored = get("ADMIN_PASSWORD_HASH")
    return hashlib.sha256(stored.encode()).hexdigest()[:16] if stored else ""


# --------------------------------------------------------------------------- user-facing options
COLOR_RE = re.compile(r"^(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20}|rgba?\(\s*[\d.\s,%]+\))$")
FILE_RE = re.compile(r"^[\w\-. ()]+$")


class Field:
    def __init__(self, name, kind, default, label, lo=None, hi=None, options=None):
        self.name, self.kind, self.default, self.label = name, kind, default, label
        self.lo, self.hi, self.options = lo, hi, options

    # Lenient: whatever is in .env, fall back to the default if it doesn't make sense.
    def read(self, raw):
        raw = (raw or "").strip()
        try:
            return self._check(self._parse(raw)) if raw else self._empty()
        except ValueError:
            return self._empty()

    # Strict: a value from the settings page -> string for .env, or ValueError with a readable message.
    def write(self, value):
        try:
            value = self._check(value)
        except (ValueError, TypeError) as e:
            raise ValueError(f"{self.label}: {e}")
        return self._format(value)

    def _empty(self):
        return list(self.default) if isinstance(self.default, list) else self.default

    def _parse(self, raw):
        k = self.kind
        if k == "bool":
            return raw.lower() in ("1", "true", "yes", "on")
        if k == "number":
            return float(raw)
        if k in ("choice", "color"):
            return raw
        if k == "names":
            return [s.strip() for s in raw.split(",") if s.strip()]
        if k == "animations":
            return [] if raw.lower() == "none" else [s.strip() for s in raw.split(",") if s.strip()]
        if k in ("files", "links"):
            out = []
            for part in raw.split(";"):
                title, sep, value = part.partition("|")
                if sep and value.strip():
                    out.append([title.strip(), value.strip()])
            return out
        raise ValueError("unknown kind")

    def _check(self, v):
        k = self.kind
        if k == "bool":
            if not isinstance(v, bool):
                raise ValueError("must be on or off")
            return v
        if k == "number":
            if isinstance(v, bool) or not isinstance(v, (int, float)):
                v = float(v)
            if not (self.lo <= v <= self.hi):
                raise ValueError(f"must be between {self.lo:g} and {self.hi:g}")
            return v
        if k == "choice":
            if v not in self.options:
                raise ValueError("must be one of " + ", ".join(self.options))
            return v
        if k == "color":
            if not isinstance(v, str) or not COLOR_RE.match(v.strip()):
                raise ValueError("must be a colour like #ffffff")
            return v.strip()
        if k == "names":
            if not isinstance(v, list) or not all(isinstance(s, str) for s in v):
                raise ValueError("must be a list of names")
            v = [s.strip() for s in v if s.strip()]
            if any("," in s or len(s) > 200 for s in v):
                raise ValueError("library names can't contain commas")
            return v
        if k == "animations":
            if not isinstance(v, list):
                raise ValueError("must be a list")
            return [a for a in v if a in ANIMATIONS]
        if k in ("files", "links"):
            if not isinstance(v, list):
                raise ValueError("must be a list")
            out = []
            for pair in v:
                if not (isinstance(pair, (list, tuple)) and len(pair) == 2):
                    raise ValueError("each entry needs a title and a value")
                title, value = str(pair[0]).strip(), str(pair[1]).strip()
                if not value:
                    continue
                if any(c in title + value for c in ";|"):
                    raise ValueError("titles and values can't contain ; or |")
                if k == "files" and (not FILE_RE.match(value) or value.startswith(".")):
                    raise ValueError(f"'{value}' isn't a plain file name")
                if k == "links":
                    u = urlparse(value)
                    if u.scheme not in ("http", "https") or not u.netloc:
                        raise ValueError(f"'{value}' isn't an http(s) link")
                out.append([title, value])
            return out
        raise ValueError("unknown kind")

    def _format(self, v):
        k = self.kind
        if k == "bool":
            return "true" if v else "false"
        if k == "number":
            return f"{v:g}"
        if k == "names":
            return ",".join(v)
        if k == "animations":
            return ",".join(v) if v else "none"
        if k in ("files", "links"):
            return ";".join(f"{t}|{val}" for t, val in v)
        return str(v)


FIELDS = [
    Field("LIBRARIES_TO_EXCLUDE", "names", [], "Libraries"),
    Field("IMAGE_INTERVAL_SECONDS", "number", 10, "Seconds per image", 3, 3600),
    Field("TRANSITION_SECONDS", "number", 1.5, "Crossfade seconds", 0, 10),
    Field("ART_QUALITY", "choice", "original", "Image quality", options=("original", "screen")),
    Field("ANIMATIONS", "animations", list(ANIMATIONS), "Motion effects"),
    Field("SHOW_TITLE", "bool", True, "Show title"),
    Field("TITLE_COLOR", "color", "#ffffff", "Title colour"),
    Field("TITLE_OPACITY", "number", 0.8, "Title opacity", 0, 1),
    Field("TITLE_POSITION", "choice", "right", "Title position", options=POSITIONS),
    Field("SHOW_TIME", "bool", True, "Show clock"),
    Field("TIME_COLOR", "color", "#ffffff", "Clock colour"),
    Field("TIME_OPACITY", "number", 0.8, "Clock opacity", 0, 1),
    Field("TIME_POSITION", "choice", "right", "Clock position", options=POSITIONS),
    Field("CLOCK_24H", "bool", True, "24-hour clock"),
    Field("SHOW_POSTER", "bool", True, "Show poster"),
    Field("PIXEL_SHIFT", "bool", True, "Burn-in protection"),
    Field("REFRESH_MINUTES", "number", 30, "Library refresh minutes", 1, 1440),
    Field("USE_PLEX", "bool", True, "Use Plex artwork"),
    Field("EXTRA_IMAGES", "files", [], "Extra local images"),
    Field("EXTRA_LINKS", "links", [], "Extra web images"),
    Field("ITEM_CACHE_SECONDS", "number", 900, "Server cache seconds", 60, 86400),
]
FIELDS_BY_NAME = {f.name: f for f in FIELDS}


def options():
    """Every user-facing option, parsed: {NAME: value}."""
    values = all_values()
    return {f.name: f.read(values.get(f.name)) for f in FIELDS}


def save_options(submitted):
    """Validate a {NAME: value} dict from the settings page and write it.
    Returns a list of {"field", "message"} errors (nothing is saved if there are any)."""
    updates, errors = {}, []
    for name, value in submitted.items():
        field = FIELDS_BY_NAME.get(name)
        if not field:
            errors.append({"field": name, "message": f"Unknown setting {name}"})
            continue
        try:
            updates[name] = field.write(value)
        except ValueError as e:
            errors.append({"field": name, "message": str(e)})
    if not errors:
        save(updates)
    return errors


def screen_config():
    """The display settings in the shape the screensaver page expects."""
    o = options()
    return {
        "imageInterval": o["IMAGE_INTERVAL_SECONDS"] * 1000,
        "transitionDuration": o["TRANSITION_SECONDS"] * 1000,
        "artQuality": o["ART_QUALITY"],
        "animations": o["ANIMATIONS"],
        "showTitle": o["SHOW_TITLE"],
        "titleColor": o["TITLE_COLOR"],
        "titleOpacity": o["TITLE_OPACITY"],
        "titlePosition": o["TITLE_POSITION"],
        "showTime": o["SHOW_TIME"],
        "timeColor": o["TIME_COLOR"],
        "timeOpacity": o["TIME_OPACITY"],
        "timePosition": o["TIME_POSITION"],
        "clock24h": o["CLOCK_24H"],
        "showLogo": o["SHOW_POSTER"],
        "pixelShift": o["PIXEL_SHIFT"],
        "refreshMinutes": o["REFRESH_MINUTES"],
        "usePlex": o["USE_PLEX"],
        "extraImages": o["EXTRA_IMAGES"],
        "extraLinks": o["EXTRA_LINKS"],
    }
