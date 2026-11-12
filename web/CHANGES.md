# Showcase Web changelog

Showcase Web was called **PlexScreen** before it joined the Showcase family. Existing installs keep working: settings, the `/screen/` address and screen keys are unchanged.

## 2.2.1 - Clear confirmation when choosing a weather town
Matches Showcase for Android TV 0.2.2.
- **The chosen town is shown in a panel with its current weather** (temperature, conditions and icon), outlined in amber
  right after you choose it, so it's clear the choice took. Once saved, it reads "Showing the weather for…".
- After choosing a town, the search box clears and the keyboard focus moves to **Save changes**.
- **Duplicate search results are merged** (a town and its borough often came back as two identical rows).
- The reading fetched for the panel is shared with the screens, so they show the weather straight after you save.
- **Version number** on the settings page (next to the heading) and in the server log at startup.
- **Screens update themselves:** when the server is updated, open screens notice within about a minute and reload,
  so nobody has to refresh the TV. This starts working from 2.2.1 on: refresh screens once more after installing it.
- **No more stale copies after an update:** the page's scripts and styles are stamped with the version, so browsers,
  TV apps and reverse proxies can't keep serving an old copy.

## 2.2 - Title logos, details and night dimming
- **Title logos:** the film or show's transparent logo artwork from Plex replaces the title text, when Plex has one.
  Falls back to the text otherwise (`TITLE_LOGOS`).
- **Detail line** under the title: year, up to two genres and the age rating, e.g. "2021 · Thriller · 15" (`SHOW_DETAILS`).
- **Night dimming:** darkens the screen between two times, eased in and out over a minute
  (`DIM_START`, `DIM_END`, `DIM_LEVEL`). Uses each screen's own clock; leave the times empty to never dim.
- Details are fetched per image as it's prepared (one small request) and cached on the server.

## 2.1 - The Showcase look
- **Same design as the Android TV app:** clock, date and weather in one corner block, light typography,
  soft shading behind the text, a bigger corner poster (16% of the width), and the title and poster fade between images.
- **Date** under the clock (`SHOW_DATE`), in the order your browser's language uses.
- **Weather** under the clock: search for your town on the settings page and choose °C or °F. The server
  fetches it every 30 minutes and shares it with all your screens, so only the town's coordinates are sent
  to Open-Meteo, never your screens' locations. New settings: `WEATHER_PLACE`, `WEATHER_LAT`, `WEATHER_LON`, `TEMP_UNIT`.
- Sizes scale with the screen, so it looks the same in a 1080p or 4K browser.

## 2.0 - What changed from the original PlexScreen

## New: settings page with "Sign in with Plex"
- **`/screen/admin/`**: an accessible settings page for everything, with a live preview.
- **Sign in with Plex** using Plex's official PIN flow. No more copying tokens out of XML. PlexScreen finds your servers, tests each address, and picks the best working one (local, then remote, then relay). This is the same flow the Android app will use.
- **Library checkboxes** pulled from your server, instead of typing names.
- **Screen key management**: generate or remove the key, and copy the TV address.
- **Protection**: local network only by default, a one-time setup code for the first password, hashed password, login lockout, CSRF protection, and tokens never sent to the browser.
- **Everything applies live**, including the Plex connection. Only `HOST`, `PORT` and `SECRET_KEY` need a restart.
- **`python app.py --set-password`** to set or reset the password from the command line.
- **Code is split up**: `app.py` (screensaver), `admin.py` (settings page), `plexapi.py` (Plex), `settings.py` (`.env`).

## Fix: works with several server processes (gunicorn -w 4)
- **Symptom:** signed out at random ("Please sign in again"), and a 500 "no secret key was set".
- **Cause:** each process created its own session key at startup, and `.env` was written in a way another process could read half-finished.
- **Fix:**
  - `.env` is written atomically, under a lock shared by all processes, keeping its owner and permissions.
  - The session key is read from `.env` on each request, so every process agrees on it.
  - Plex sign-ins in progress, login lockouts and the setup code live in a shared owner-only file (`.plexscreen-state.json`).
  - The setup code is now also logged under gunicorn and stays the same across restarts.
- **Tested** with 4 processes started at the same moment, and 120 simultaneous saves plus 240 reads across them: no errors, `.env` intact.

## Things you need to do differently
1. **All settings live in `.env`**, which the settings page writes for you. That includes the display options that used to be in the `config` block of the JS. You can still edit `.env` by hand; every option is explained in `.env.example`.
2. **No more hard-coded server.** The old code fell back to a personal address; now you connect via the settings page or set `PLEX_BASE_URL`.
3. **`runtime.js` is gone.** The page now loads the readable `js/PlexScreen.js` directly. Obfuscation added no protection, because the token was never in the browser.
4. **`flask-cors` is no longer needed.** `waitress`, a production web server, is now used instead of Flask's development server.

## Bugs fixed
| Problem | Effect | Fix |
|---|---|---|
| Music/photo libraries never "finished" loading | The slideshow never started if you had one | The server builds the list and simply skips them |
| One library failing to load | Same: the slideshow never started | That library is skipped and logged; the rest still show |
| The slideshow could start twice | Two timers ran, so images swapped at double speed | A guard ensures only one loop |
| An error response was still parsed as XML | Confusing follow-on errors | Errors are handled once, with a clear message |
| `err === "fetch failed"` | The "check your IP" hint never showed | Errors now come from the server with specific messages |
| `useExtraLinks: true` | Crash (`extraLinks` was undefined) | Defined, and works |
| Broken or missing image | A broken image showed for a full slide | It's skipped; the corner poster hides if it fails |
| Clock | Hours not zero-padded; updated every second | Padded, updates on the minute, optional 12h mode |
| `index.html` | Missing `<body>` tag | Fixed |
| Title/clock position setting | Did nothing: the elements were only as wide as their text | They now span the screen, so left/center/right work |
| A network error with no retry | A blank screen until someone reloaded it | Retries automatically, waiting longer each time (up to 5 min) |

## Security
- **Optional access key** (`SCREEN_ACCESS_KEY`). Before, anyone who could reach the URL could list your whole library and download artwork through your token.
- **CORS removed.** Before, any website could make your browser query the proxy.
- **The token is sent to Plex in a header**, not in the URL, so it doesn't end up in logs.
- **IDs must be numbers.** Before, a crafted ID could alter the request sent to Plex.
- **Static files are served safely** (no `../` tricks), and the API paths can't be mistaken for files.
- **Timeouts on every Plex request**, so a slow Plex server can't hang the proxy.
- **A real `.env` is never shipped**, only `.env.example`.

## Performance
- **One request instead of many.** The server fetches only the fields it needs (title, ID, art) and caches the list for 15 minutes, instead of every screen downloading every library's full XML on each load.
- **Shuffle without repeats.** Every title is shown once before any repeats; before, random picks repeated often.
- **Images preload during the wait**, so swaps happen on time and don't stutter.
- **Crossfade without a dip to black.** The new image fades in over the old one.
- **New Plex items appear automatically** (list refresh every 30 min); before, a reload was needed.
- **Images are cached by the browser for a day**, so repeat images don't hit Plex again.
- **Image quality is your choice:** original artwork by default (`artQuality: "original"`), or `"screen"` to have Plex shrink it for weaker devices.

## New options (in `.env`)
- `ART_QUALITY`, `CLOCK_24H`, `SHOW_POSTER`, `PIXEL_SHIFT` (OLED burn-in protection), `REFRESH_MINUTES`, `USE_PLEX`, `EXTRA_IMAGES`, `EXTRA_LINKS`
