"""Showcase Web - a fullscreen slideshow of your Plex artwork.

    python app.py                  start the server
    python app.py --set-password   set/reset the settings-page password from the command line

The screensaver is at /screen/, the settings page at /screen/admin/.
The Plex token never leaves this server: browsers only talk to /screen/api/*.
"""
import argparse
import getpass
import hashlib
import hmac
import logging
import os
import random
import sys
import threading
import time
from datetime import timedelta

import requests
from flask import Blueprint, Flask, abort, jsonify, make_response, request, send_from_directory
from flask.sessions import SecureCookieSessionInterface
from itsdangerous import URLSafeTimedSerializer

import admin
import plexapi
import settings
import weather

STATIC_DIR = os.path.join(settings.BASE_DIR, "static")
MAX_IMAGE_DIM = 3840
KEY_COOKIE = "showcase_key"
LEGACY_KEY_COOKIE = "plexscreen_key"      # set by the old PlexScreen name - still accepted

log = logging.getLogger("showcase")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

settings.init_secrets()


class LiveKeySessions(SecureCookieSessionInterface):
    """Sign session cookies with the key from .env, read per request - so every server process
    (gunicorn workers, restarts) agrees on it, and changing SECRET_KEY in .env signs everyone out."""

    def get_signing_serializer(self, app):
        return URLSafeTimedSerializer(
            settings.secret_key(), salt=self.salt, serializer=self.serializer,
            signer_kwargs={"key_derivation": self.key_derivation, "digest_method": self.digest_method})


app = Flask(__name__, static_folder=None)
app.session_interface = LiveKeySessions()
app.config.update(
    SESSION_COOKIE_NAME="showcase_session",
    SESSION_COOKIE_PATH="/screen",
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    PERMANENT_SESSION_LIFETIME=timedelta(hours=12),
    MAX_CONTENT_LENGTH=64 * 1024,
)
screen_bp = Blueprint("screen", __name__)


# --------------------------------------------------------------------------- screen access key
def _key_ok(value, key):
    return bool(value) and hmac.compare_digest(value.encode(), key.encode())


@screen_bp.before_request
def require_key():
    """If SCREEN_ACCESS_KEY is set: need ?key=... once (then remembered in a cookie). Signed-in admins pass."""
    key = settings.get("SCREEN_ACCESS_KEY")
    if not key or admin.logged_in():
        return None
    cookie = request.cookies.get(KEY_COOKIE) or request.cookies.get(LEGACY_KEY_COOKIE, "")
    if _key_ok(cookie, key) or _key_ok(request.args.get("key", ""), key):
        return None
    abort(401)


@screen_bp.after_request
def remember_key(resp):
    key = settings.get("SCREEN_ACCESS_KEY")
    if key and _key_ok(request.args.get("key", ""), key):
        resp.set_cookie(KEY_COOKIE, key, max_age=60 * 60 * 24 * 365, path="/screen",
                        httponly=True, samesite="Lax", secure=request.is_secure)
    return resp


# --------------------------------------------------------------------------- library list (cached)
_cache = {"items": None, "at": 0.0, "key": None}
_cache_lock = threading.Lock()


def _load_items(excluded):
    items = []
    for section in plexapi.pms_json("/library/sections").get("Directory", []):
        title, stype, key = section.get("title"), section.get("type"), section.get("key")
        if title in excluded or stype not in ("movie", "show"):
            continue
        try:
            container = plexapi.pms_json(f"/library/sections/{key}/all",
                                         includeFields="ratingKey,title,art,thumb")
        except requests.RequestException as e:
            log.warning("Skipping library %r: %s", title, e)     # one bad library doesn't stop the rest
            continue
        before = len(items)
        for m in container.get("Metadata", []):
            if m.get("art") and m.get("ratingKey"):
                items.append({"key": str(m["ratingKey"]), "title": m.get("title", ""),
                              "hasThumb": bool(m.get("thumb"))})
        log.info("Library %r: %d items with artwork", title, len(items) - before)
    return items


def get_items():
    base, token = plexapi.server()
    excluded = frozenset(settings.options()["LIBRARIES_TO_EXCLUDE"])
    max_age = settings.options()["ITEM_CACHE_SECONDS"]
    # Changing server, token or excluded libraries invalidates the cache immediately.
    cache_key = (base, hashlib.sha256(token.encode()).hexdigest(), excluded)
    with _cache_lock:
        fresh = (_cache["items"] is not None and _cache["key"] == cache_key
                 and time.time() - _cache["at"] < max_age)
        if not fresh:
            try:
                _cache.update(items=_load_items(excluded), key=cache_key, at=time.time())
            except (requests.RequestException, ValueError, KeyError) as e:
                if _cache["items"] is None or _cache["key"] != cache_key:
                    raise
                log.warning("Plex refresh failed, serving the cached list: %s", e)
                _cache["at"] = time.time() - max_age + 60      # retry in a minute
        return _cache["items"]


# --------------------------------------------------------------------------- routes
@screen_bp.route("/api/weather")
def api_weather():
    resp = jsonify(weather=weather.current())
    resp.headers["Cache-Control"] = "no-store"
    return resp


@screen_bp.route("/api/config")
def api_config():
    resp = jsonify(settings.screen_config())
    resp.headers["Cache-Control"] = "no-store"
    return resp


@screen_bp.route("/api/items")
def api_items():
    try:
        items = list(get_items())
    except plexapi.NotConfigured:
        return jsonify(error="Not set up yet - open " + request.url_root + "screen/admin/ to connect Plex"), 503
    except requests.HTTPError as e:
        status = e.response.status_code if e.response is not None else 502
        msg = ("Plex rejected the saved token - sign in again on the settings page" if status == 401
               else f"Plex returned error {status}")
        return jsonify(error=msg), 502
    except (requests.RequestException, ValueError, KeyError) as e:
        log.error("Could not reach Plex: %s", e)
        return jsonify(error="Could not reach the Plex server - check the settings page"), 502
    random.shuffle(items)
    resp = jsonify(items=items)
    resp.headers["Cache-Control"] = "no-store"
    return resp


def _clamp(value, default):
    try:
        return max(64, min(int(value), MAX_IMAGE_DIM))
    except (TypeError, ValueError):
        return default


@screen_bp.route("/api/item/<int:rating_key>/<media_type>")
def api_image(rating_key, media_type):
    if media_type not in ("art", "thumb"):
        abort(404)
    source = f"/library/metadata/{rating_key}/{media_type}"
    try:
        r = None
        if request.args.get("w"):
            # Resize requested: Plex scales the image down to fit (never up).
            r = plexapi.pms_image("/photo/:/transcode", {
                "width": _clamp(request.args.get("w"), 1920), "height": _clamp(request.args.get("h"), 1080),
                "minSize": 1, "upscale": 0, "url": source})
        if r is None or r.status_code != 200:
            r = plexapi.pms_image(source)                     # original artwork, untouched
    except plexapi.NotConfigured:
        abort(503)
    except requests.RequestException as e:
        log.warning("Image %s/%s failed: %s", rating_key, media_type, e)
        abort(502)
    if r.status_code != 200:
        abort(404 if r.status_code == 404 else 502)
    resp = make_response(r.content)
    resp.headers["Content-Type"] = r.headers.get("Content-Type", "image/jpeg")
    private = settings.get("SCREEN_ACCESS_KEY") or admin.logged_in()
    resp.headers["Cache-Control"] = ("private" if private else "public") + ", max-age=86400"
    return resp


@screen_bp.route("/", defaults={"path": "index.html"})
@screen_bp.route("/<path:path>")
def serve_static(path):
    if path.startswith("api/"):
        abort(404)
    if not os.path.isfile(os.path.join(STATIC_DIR, path)):
        path = "index.html"
    return send_from_directory(STATIC_DIR, path)       # safe against ../ traversal


app.register_blueprint(admin.admin_bp, url_prefix="/screen/admin")
app.register_blueprint(screen_bp, url_prefix="/screen")

# Logged at import, so it also shows up when run by gunicorn (systemd: journalctl -u <service>).
if not settings.get("ADMIN_PASSWORD_HASH") and "--set-password" not in sys.argv:
    log.info("FIRST RUN: open /screen/admin/ and enter setup code  %s  to create your password "
             "(or run: python3 app.py --set-password)", admin.setup_code())


# --------------------------------------------------------------------------- command line
def set_password_cli():
    pw = getpass.getpass("New settings-page password: ")
    if len(pw) < admin.MIN_PASSWORD:
        sys.exit(f"Use at least {admin.MIN_PASSWORD} characters.")
    if getpass.getpass("Again: ") != pw:
        sys.exit("Passwords didn't match.")
    admin.set_password(pw)
    print("Saved. Any signed-in browsers will need to sign in again.")


def main():
    parser = argparse.ArgumentParser(description="Showcase Web server")
    parser.add_argument("--set-password", action="store_true", help="set the settings-page password")
    args = parser.parse_args()
    if args.set_password:
        set_password_cli()
        return

    host, port = settings.get("HOST", "0.0.0.0"), int(settings.get("PORT", "5005"))
    shown = "localhost" if host in ("0.0.0.0", "::") else host
    log.info("Screensaver:   http://%s:%d/screen/", shown, port)
    log.info("Settings page: http://%s:%d/screen/admin/", shown, port)
    try:
        from waitress import serve
        # Keep X-Forwarded-For etc. so the settings page can tell proxied (remote) requests apart.
        serve(app, host=host, port=port, threads=8, clear_untrusted_proxy_headers=False)
    except ImportError:
        app.run(host=host, port=port)


if __name__ == "__main__":
    main()
