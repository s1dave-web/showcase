"""Talking to Plex: your Plex Media Server, and plex.tv for sign-in and server discovery.

Sign-in uses Plex's PIN flow (the same one the Android app will use):
  1. create_pin()           -> a PIN id + code
  2. the user approves it on app.plex.tv (auth_url)
  3. check_pin(id)          -> the account token, once approved
  4. list_servers(token)    -> the servers that account can use, with all their addresses
  5. pick_connection(...)   -> tests each address, picks the best working one (LAN > remote > relay)
"""
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlencode, urlparse

import requests

import settings

PLEX_TV = "https://plex.tv"
CLIENTS = "https://clients.plex.tv"
PRODUCT = "Showcase Web"
TIMEOUT = (5, 20)          # connect, read
TEST_TIMEOUT = (3, 5)

http = requests.Session()


class NotConfigured(Exception):
    pass


def headers(token=None, accept="application/json"):
    h = {
        "Accept": accept,
        "X-Plex-Product": PRODUCT,
        "X-Plex-Version": "2.0",
        "X-Plex-Client-Identifier": settings.get("PLEX_CLIENT_ID", "showcase-web"),
        "X-Plex-Device-Name": PRODUCT,
        "X-Plex-Platform": "Web",
    }
    if token:
        h["X-Plex-Token"] = token          # header, so the token never appears in URLs or logs
    return h


# --------------------------------------------------------------------------- your Plex server
def server():
    base, token = settings.get("PLEX_BASE_URL").rstrip("/"), settings.get("PLEX_TOKEN")
    if not base or not token:
        raise NotConfigured()
    return base, token


def pms_json(path, **params):
    base, token = server()
    r = http.get(f"{base}{path}", params=params, headers=headers(token), timeout=TIMEOUT)
    r.raise_for_status()
    return r.json()["MediaContainer"]


def pms_image(path, params=None):
    base, token = server()
    return http.get(f"{base}{path}", params=params, headers=headers(token, "image/*"), timeout=TIMEOUT)


def libraries():
    return [{"title": d.get("title", ""), "type": d.get("type", ""),
             "supported": d.get("type") in ("movie", "show")}
            for d in pms_json("/library/sections").get("Directory", [])]


def status():
    """Quick health check of the saved server."""
    try:
        base, token = server()
    except NotConfigured:
        return {"configured": False}
    info = {"configured": True, "url": base,
            "serverName": settings.get("PLEX_SERVER_NAME"), "username": settings.get("PLEX_USERNAME")}
    try:
        r = http.get(f"{base}/", headers=headers(token), timeout=TEST_TIMEOUT)
        info["reachable"] = r.ok
        if r.status_code == 401:
            info["error"] = "Plex rejected the saved token - sign in again"
        elif r.ok:
            info["serverName"] = r.json()["MediaContainer"].get("friendlyName") or info["serverName"]
        else:
            info["error"] = f"Plex answered with error {r.status_code}"
    except (requests.RequestException, ValueError, KeyError):
        info["reachable"] = False
        info["error"] = "Can't reach the Plex server at the saved address"
    return info


# --------------------------------------------------------------------------- plex.tv sign-in
def create_pin():
    r = http.post(f"{PLEX_TV}/api/v2/pins", params={"strong": "true"}, headers=headers(), timeout=TIMEOUT)
    r.raise_for_status()
    d = r.json()
    return d["id"], d["code"]


def auth_url(code, forward_url=None):
    params = {"clientID": settings.get("PLEX_CLIENT_ID"), "code": code,
              "context[device][product]": PRODUCT}
    if forward_url:
        params["forwardUrl"] = forward_url
    return "https://app.plex.tv/auth#?" + urlencode(params)


def check_pin(pin_id):
    """Returns the account token once the user has approved the PIN, else None."""
    r = http.get(f"{PLEX_TV}/api/v2/pins/{int(pin_id)}", headers=headers(), timeout=TIMEOUT)
    r.raise_for_status()
    return r.json().get("authToken") or None


def account_name(token):
    r = http.get(f"{PLEX_TV}/api/v2/user", headers=headers(token), timeout=TIMEOUT)
    r.raise_for_status()
    d = r.json()
    return d.get("username") or d.get("title") or d.get("email") or "Plex user"


def list_servers(token):
    r = http.get(f"{CLIENTS}/api/v2/resources", params={"includeHttps": 1, "includeRelay": 1},
                 headers=headers(token), timeout=TIMEOUT)
    r.raise_for_status()
    return [res for res in r.json() if "server" in (res.get("provides") or "").split(",")]


def public_server_info(res):
    """What the settings page may see about a server (no tokens)."""
    kinds = sorted({_kind(c) for c in res.get("connections", [])}, key=RANK.get)
    return {"id": res.get("clientIdentifier"), "name": res.get("name") or "Plex server",
            "owned": bool(res.get("owned")), "owner": res.get("sourceTitle") or "",
            "online": res.get("presence", True), "kinds": kinds}


# --------------------------------------------------------------------------- choosing an address
RANK = {"local": 0, "remote": 1, "relay": 2}


def _kind(c):
    return "relay" if c.get("relay") else ("local" if c.get("local") else "remote")


def candidates(res):
    """Every address worth trying for a server, best kind first."""
    out, seen = [], set()
    for c in res.get("connections", []):
        kind = _kind(c)
        uris = [c.get("uri")]
        if kind == "local" and c.get("address") and c.get("port"):
            # Plain http://ip:port too - some routers block the *.plex.direct names used by the https address.
            host = c["address"]
            host = f"[{host}]" if ":" in host else host
            uris.append(f"http://{host}:{c['port']}")
        for uri in uris:
            if uri and uri.rstrip("/") not in seen:
                seen.add(uri.rstrip("/"))
                out.append((uri.rstrip("/"), kind))
    return sorted(out, key=lambda x: RANK[x[1]])


def test_address(uri, token, machine_id=None):
    """Does this address answer, accept the token, and belong to the expected server?"""
    started = time.monotonic()
    try:
        r = http.get(f"{uri}/", headers=headers(token), timeout=TEST_TIMEOUT)
        ok = r.ok
        name = None
        if ok:
            mc = r.json()["MediaContainer"]
            name = mc.get("friendlyName")
            if machine_id and mc.get("machineIdentifier") not in (None, machine_id):
                ok = False
        error = None if ok else ("Token rejected" if r.status_code == 401 else f"HTTP {r.status_code}")
    except (requests.RequestException, ValueError, KeyError) as e:
        ok, name, error = False, None, type(e).__name__
    return {"uri": uri, "ok": ok, "ms": int((time.monotonic() - started) * 1000), "name": name, "error": error}


def pick_connection(res):
    """Test every address in parallel; return (best_result_or_None, all_results)."""
    token, machine_id = res.get("accessToken"), res.get("clientIdentifier")
    cands = candidates(res)
    if not cands:
        return None, []
    with ThreadPoolExecutor(max_workers=min(8, len(cands))) as pool:
        results = list(pool.map(lambda c: {**test_address(c[0], token, machine_id), "kind": c[1]}, cands))
    working = [r for r in results if r["ok"]]
    best = min(working, key=lambda r: (RANK[r["kind"]], r["ms"])) if working else None
    return best, results


def valid_server_url(url):
    u = urlparse(url)
    return u.scheme in ("http", "https") and bool(u.netloc) and not u.query and not u.fragment
