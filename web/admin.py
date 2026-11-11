"""The settings page at /screen/admin/.

Protection:
- Only reachable from your local network unless ADMIN_ALLOW_REMOTE=true in .env.
  Requests that came through a reverse proxy count as remote.
- Password (stored as a scrypt hash in .env). The first visit from your LAN creates it.
- Signed session cookie; ends when the password changes. CSRF token + Origin check on every change.
- Wrong passwords are rate-limited per IP.
- Plex tokens never reach the browser.
"""
import functools
import hmac
import ipaddress
import logging
import os
import secrets
import time
from urllib.parse import urlparse

import requests
from flask import Blueprint, abort, jsonify, request, send_from_directory, session

import plexapi
import settings

log = logging.getLogger("showcase")
ADMIN_DIR = os.path.join(settings.BASE_DIR, "admin")
MIN_PASSWORD = 8

admin_bp = Blueprint("admin", __name__)


# --------------------------------------------------------------------------- who is asking
def is_lan():
    if any(h in request.headers for h in ("X-Forwarded-For", "X-Real-IP", "Forwarded")):
        return False                    # came through a proxy - can't trust it's local
    try:
        ip = ipaddress.ip_address(request.remote_addr or "")
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_private or ip.is_loopback or ip.is_link_local


def logged_in():
    fp = settings.password_fingerprint()
    return bool(fp) and session.get("admin") is True and session.get("pw") == fp


def require_login(view):
    @functools.wraps(view)
    def wrapper(*a, **kw):
        if not logged_in():
            return jsonify(error="Please sign in again"), 401
        return view(*a, **kw)
    return wrapper


@admin_bp.before_request
def gate():
    if not (is_lan() or settings.flag("ADMIN_ALLOW_REMOTE")):
        return ("The Showcase Web settings page is only available from your local network. "
                "(Set ADMIN_ALLOW_REMOTE=true in .env to change this.)"), 403
    if request.method == "POST":
        origin = request.headers.get("Origin")
        if origin and urlparse(origin).netloc != request.host:
            abort(403)
        token = session.get("csrf", "")
        if not token or not hmac.compare_digest(request.headers.get("X-CSRF-Token", ""), token):
            return jsonify(error="Your session expired - reload the page"), 403


@admin_bp.after_request
def security_headers(resp):
    resp.headers["Cache-Control"] = "no-store"
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["Referrer-Policy"] = "no-referrer"
    resp.headers["Content-Security-Policy"] = (
        "default-src 'self'; img-src 'self' data:; frame-src 'self'; frame-ancestors 'none'; "
        "form-action 'self'; base-uri 'none'")
    return resp


def body():
    return request.get_json(silent=True) or {}


# --------------------------------------------------------------------------- brute-force protection
# Kept in the shared state file so the limit holds across all server processes.
def _locked_for():
    with settings.shared_state() as s:
        rec = s.get("fails", {}).get(request.remote_addr)
        return max(0, int(rec[1] - time.time())) if rec else 0


def _failed():
    now = time.time()
    with settings.shared_state() as s:
        fails = s.setdefault("fails", {})
        for ip in [ip for ip, r in fails.items() if now - r[2] > 86400]:
            del fails[ip]                            # forget day-old failures
        rec = fails.setdefault(request.remote_addr, [0, 0.0, now])
        rec[0] += 1
        rec[2] = now
        if rec[0] >= 5:                              # 5 tries, then 1, 2, 4... minutes (max 1h)
            rec[1] = now + min(3600, 60 * 2 ** (rec[0] - 5))
    time.sleep(0.5)


def _succeeded():
    with settings.shared_state() as s:
        s.get("fails", {}).pop(request.remote_addr, None)


def set_password(password, only_if_unset=False):
    """Store a new admin password (ends every login session). Returns False if one exists and only_if_unset."""
    with settings.file_lock():
        if only_if_unset and settings.get("ADMIN_PASSWORD_HASH"):
            return False
        settings.save({"ADMIN_PASSWORD_HASH": settings.hash_password(password)})
        with settings.shared_state() as s:
            s.pop("setup_code", None)
    return True


def _start_session():
    session.clear()
    session.permanent = True
    session.update(admin=True, pw=settings.password_fingerprint(),
                   sid=secrets.token_hex(16), csrf=secrets.token_urlsafe(32))
    return session["csrf"]


# --------------------------------------------------------------------------- page
@admin_bp.route("/")
def page():
    return send_from_directory(ADMIN_DIR, "index.html")


@admin_bp.route("/<any('admin.js','admin.css'):name>")
def asset(name):
    return send_from_directory(ADMIN_DIR, name)


# --------------------------------------------------------------------------- account
@admin_bp.route("/api/state")
def state():
    if "csrf" not in session:
        session["csrf"] = secrets.token_urlsafe(32)
    return jsonify(setupNeeded=not settings.get("ADMIN_PASSWORD_HASH"), loggedIn=logged_in(),
                   csrf=session["csrf"], lan=is_lan())


def setup_code():
    """One-time code printed in the server log; needed to create the first password.
    Proves the person setting up can see the server itself, even if a proxy hides their real address.
    Shared by all server processes, and stays the same across restarts until a password is set."""
    with settings.shared_state() as s:
        if not s.get("setup_code"):
            s["setup_code"] = "-".join(secrets.token_hex(2).upper() for _ in range(2))
        return s["setup_code"]


@admin_bp.route("/api/setup", methods=["POST"])
def setup():
    if settings.get("ADMIN_PASSWORD_HASH"):
        return jsonify(error="A password is already set"), 409
    if not is_lan():
        return jsonify(error="The first password can only be created from your local network"), 403
    wait = _locked_for()
    if wait:
        return jsonify(error=f"Too many attempts - try again in {wait} seconds"), 429
    code = str(body().get("code", "")).strip().upper().replace(" ", "")
    if not hmac.compare_digest(code.encode(), setup_code().encode()):
        _failed()
        return jsonify(error="Wrong setup code. It's shown in the window where the server is running."), 401
    pw = str(body().get("password", ""))
    if len(pw) < MIN_PASSWORD:
        return jsonify(error=f"Use at least {MIN_PASSWORD} characters"), 400
    if not set_password(pw, only_if_unset=True):
        return jsonify(error="A password is already set"), 409
    log.info("Admin password created from %s", request.remote_addr)
    return jsonify(csrf=_start_session())


@admin_bp.route("/api/login", methods=["POST"])
def login():
    wait = _locked_for()
    if wait:
        return jsonify(error=f"Too many attempts - try again in {wait} seconds"), 429
    stored = settings.get("ADMIN_PASSWORD_HASH")
    if not stored or not settings.check_password(str(body().get("password", "")), stored):
        _failed()
        log.warning("Failed settings login from %s", request.remote_addr)
        return jsonify(error="Wrong password"), 401
    _succeeded()
    return jsonify(csrf=_start_session())


@admin_bp.route("/api/logout", methods=["POST"])
def logout():
    _drop_pending()
    session.clear()
    session["csrf"] = secrets.token_urlsafe(32)
    return jsonify(csrf=session["csrf"])


@admin_bp.route("/api/password", methods=["POST"])
@require_login
def change_password():
    data = body()
    if not settings.check_password(str(data.get("current", "")), settings.get("ADMIN_PASSWORD_HASH")):
        _failed()
        return jsonify(error="Current password is wrong"), 400
    new = str(data.get("new", ""))
    if len(new) < MIN_PASSWORD:
        return jsonify(error=f"Use at least {MIN_PASSWORD} characters"), 400
    set_password(new)
    return jsonify(csrf=_start_session())          # other devices are signed out


# --------------------------------------------------------------------------- settings
def _screen_url(key):
    return request.url_root + "screen/" + (f"?key={key}" if key else "")


@admin_bp.route("/api/settings")
@require_login
def get_settings():
    key = settings.get("SCREEN_ACCESS_KEY")
    return jsonify(values=settings.options(), screenKeySet=bool(key), screenUrl=_screen_url(key),
                   remoteAllowed=settings.flag("ADMIN_ALLOW_REMOTE"))


@admin_bp.route("/api/settings", methods=["POST"])
@require_login
def post_settings():
    values = body().get("values")
    if not isinstance(values, dict):
        return jsonify(error="Nothing to save"), 400
    errors = settings.save_options(values)
    if errors:
        return jsonify(error="Not saved", errors=errors), 400
    return jsonify(values=settings.options())


@admin_bp.route("/api/screen-key", methods=["POST"])
@require_login
def screen_key():
    action = body().get("action")
    if action == "generate":
        key = secrets.token_urlsafe(18)
    elif action == "clear":
        key = ""
    else:
        return jsonify(error="Unknown action"), 400
    settings.save({"SCREEN_ACCESS_KEY": key})
    return jsonify(screenKeySet=bool(key), screenUrl=_screen_url(key))


@admin_bp.route("/api/libraries")
@require_login
def get_libraries():
    try:
        return jsonify(libraries=plexapi.libraries())
    except plexapi.NotConfigured:
        return jsonify(error="Connect to Plex first"), 409
    except (requests.RequestException, ValueError, KeyError):
        return jsonify(error="Couldn't load libraries from Plex"), 502


# --------------------------------------------------------------------------- Plex connection
# A Plex sign-in in progress, per login session. It holds tokens, so it lives server-side only:
# in the shared state file (owner-only permissions), so whichever server process answers can continue it.
PENDING_SECONDS = 15 * 60


def _mine():
    sid, now = session.get("sid"), time.time()
    with settings.shared_state() as s:
        pending = s.setdefault("pending", {})
        for key in [k for k, p in pending.items() if p.get("expires", 0) < now]:
            del pending[key]
        p = pending.get(sid)
        return dict(p) if p else None


def _keep(p):
    with settings.shared_state() as s:
        s.setdefault("pending", {})[session["sid"]] = p


def _drop_pending():
    with settings.shared_state() as s:
        s.get("pending", {}).pop(session.get("sid"), None)


@admin_bp.route("/api/plex/status")
@require_login
def plex_status():
    return jsonify(plexapi.status())


@admin_bp.route("/api/plex/pin", methods=["POST"])
@require_login
def plex_pin():
    try:
        pin_id, code = plexapi.create_pin()
    except (requests.RequestException, ValueError, KeyError):
        return jsonify(error="Couldn't reach plex.tv - check this server's internet connection"), 502
    _keep({"pin": pin_id, "token": None, "user": None, "servers": [], "expires": time.time() + PENDING_SECONDS})
    forward = request.url_root + "screen/admin/?plex=done"
    return jsonify(authUrl=plexapi.auth_url(code, forward))


@admin_bp.route("/api/plex/pin")
@require_login
def plex_pin_status():
    p = _mine()
    if not p:
        return jsonify(status="expired")
    if not p["token"]:
        try:
            token = plexapi.check_pin(p["pin"])
        except (requests.RequestException, ValueError):
            return jsonify(status="waiting")
        if not token:
            return jsonify(status="waiting")
        try:
            p["user"] = plexapi.account_name(token)
            p["servers"] = plexapi.list_servers(token)
        except (requests.RequestException, ValueError, KeyError):
            return jsonify(error="Signed in, but couldn't load your servers from plex.tv"), 502
        p["token"] = token
        _keep(p)
    return jsonify(status="done", user=p["user"],
                   servers=[plexapi.public_server_info(s) for s in p["servers"]])


@admin_bp.route("/api/plex/connect", methods=["POST"])
@require_login
def plex_connect():
    p = _mine()
    if not p or not p["token"]:
        return jsonify(error="Sign in to Plex first"), 409
    res = next((s for s in p["servers"] if s.get("clientIdentifier") == body().get("serverId")), None)
    if not res:
        return jsonify(error="Unknown server"), 404
    best, results = plexapi.pick_connection(res)
    tested = [{k: r[k] for k in ("uri", "kind", "ok", "ms", "error")} for r in results]
    if not best:
        return jsonify(error=f"None of {res.get('name')}'s addresses answered from this machine", tested=tested), 502
    settings.save({"PLEX_TOKEN": res["accessToken"], "PLEX_BASE_URL": best["uri"],
                   "PLEX_SERVER_NAME": res.get("name") or best.get("name") or "",
                   "PLEX_SERVER_ID": res.get("clientIdentifier") or "", "PLEX_USERNAME": p["user"] or ""})
    log.info("Connected to Plex server %r at %s (%s)", res.get("name"), best["uri"], best["kind"])
    _drop_pending()
    return jsonify(ok=True, serverName=res.get("name"), uri=best["uri"], kind=best["kind"], tested=tested)


@admin_bp.route("/api/plex/manual", methods=["POST"])
@require_login
def plex_manual():
    data = body()
    url = str(data.get("url", "")).strip().rstrip("/")
    token = str(data.get("token", "")).strip() or settings.get("PLEX_TOKEN")
    if not plexapi.valid_server_url(url):
        return jsonify(error="Enter an address like http://192.168.1.20:32400"), 400
    if not token:
        return jsonify(error="Enter a token, or sign in with Plex instead"), 400
    result = plexapi.test_address(url, token)
    if not result["ok"]:
        return jsonify(error=f"That didn't work ({result['error']})"), 502
    settings.save({"PLEX_TOKEN": token, "PLEX_BASE_URL": url, "PLEX_SERVER_NAME": result.get("name") or "",
                   "PLEX_SERVER_ID": "", "PLEX_USERNAME": ""})
    return jsonify(ok=True, serverName=result.get("name"), uri=url)


@admin_bp.route("/api/plex/disconnect", methods=["POST"])
@require_login
def plex_disconnect():
    settings.save({"PLEX_TOKEN": "", "PLEX_SERVER_NAME": "", "PLEX_SERVER_ID": "", "PLEX_USERNAME": ""})
    return jsonify(ok=True)
