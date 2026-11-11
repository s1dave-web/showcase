"""Weather for the screens, from Open-Meteo (open-meteo.com): free, no account or API key.

The server fetches it (at most every 30 minutes) and every screen reads it from /screen/api/weather,
so the chosen town never leaves the server and Open-Meteo sees one request, however many screens you have.
Data licensed CC BY 4.0 - credited on the settings page.
"""
import logging
import threading
import time

import requests

import settings

log = logging.getLogger("showcase")
GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST = "https://api.open-meteo.com/v1/forecast"
TIMEOUT = (5, 15)
REFRESH_SECONDS = 30 * 60
MAX_AGE_SECONDS = 3 * 60 * 60          # older than this isn't worth showing

_cache = {"key": None, "at": 0.0, "data": None}
_lock = threading.Lock()


def search(query):
    """Towns matching the query: [{name, detail, label, lat, lon}]."""
    r = requests.get(GEOCODING, params={"name": query.strip(), "count": 8, "language": "en", "format": "json"},
                     timeout=TIMEOUT)
    r.raise_for_status()
    out, seen = [], set()
    for p in r.json().get("results") or []:
        detail = ", ".join(x for x in (p.get("admin1"), p.get("country")) if x)
        name = p.get("name", "")
        if (name, detail) in seen:          # a town and its borough often come back as two identical rows
            continue
        seen.add((name, detail))
        out.append({"name": name, "detail": detail, "lat": p["latitude"], "lon": p["longitude"],
                    "label": f"{name}, {detail.split(',')[0]}" if detail else name})
    return out


def _fetch(lat, lon):
    r = requests.get(FORECAST, timeout=TIMEOUT, params={
        "latitude": lat, "longitude": lon,
        "current": "temperature_2m,weather_code,is_day", "timezone": "auto"})
    r.raise_for_status()
    c = r.json()["current"]
    return {"tempC": c["temperature_2m"], "code": c["weather_code"], "isDay": c.get("is_day", 1) == 1}


def current():
    """{tempC, code, isDay} for the chosen town, or None when weather is off or unavailable."""
    o = settings.options()
    if not settings.weather_enabled(o):
        return None
    key = (float(o["WEATHER_LAT"]), float(o["WEATHER_LON"]))
    with _lock:
        now = time.time()
        if _cache["key"] == key and now - _cache["at"] < REFRESH_SECONDS:
            return _cache["data"]
        try:
            data = _fetch(*key)
            _cache.update(key=key, at=now, data=data)
            return data
        except (requests.RequestException, ValueError, KeyError) as e:
            log.warning("Weather update failed: %s", e)
            if _cache["key"] == key and now - _cache["at"] < MAX_AGE_SECONDS:
                return _cache["data"]
            return None


def at(lat, lon):
    """Current weather for any town, for the settings page's confirmation. Raises if Open-Meteo can't be reached.

    The reading is cached as if the town were already saved, so once it is, the screens show it without another request.
    """
    key = (float(lat), float(lon))
    with _lock:
        now = time.time()
        if _cache["key"] == key and now - _cache["at"] < REFRESH_SECONDS:
            return _cache["data"]
        data = _fetch(*key)
        _cache.update(key=key, at=now, data=data)
        return data
