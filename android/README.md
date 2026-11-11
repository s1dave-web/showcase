# Showcase for Android TV

Your Plex movie and TV artwork as an Android TV screen saver. Sign in with Plex, pick a server, done.
There's no Showcase Web server in between: the app talks to your Plex server directly.

It's a native Kotlin port of Showcase Web (the self-hosted version in `../web`), with the same slideshow (crossfades, slow pan/zoom,
colour fades, shuffle without repeats, title, clock, corner poster, burn-in protection) and the same
"Sign in with Plex" plus best-address logic.

## Build it
1. Install **Android Studio** (developer.android.com/studio) and let it finish its first-run setup.
2. **File → Open** the `android` folder of this repo. Let Gradle sync (the first time downloads a few hundred MB).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Install it on an Nvidia Shield (sideload)
1. On the Shield: **Settings → Device Preferences → About**, then select **Build** 7 times to enable developer options.
2. **Settings → Device Preferences → Developer options**: turn on **Network debugging** and note the IP address shown.
3. On the PC (adb comes with Android Studio, in `%LOCALAPPDATA%\Android\Sdk\platform-tools`):
   ```
   adb connect <shield-ip>:5555
   adb install -r app-debug.apk
   ```
   Accept the "Allow debugging?" prompt on the TV. Or press **Run ▶** in Android Studio with the Shield selected.
4. Open **Showcase** from the TV home screen (it's at the end of the apps row) and sign in.

## Set it as the screen saver
- **Shield / standard Android TV:** in Showcase choose **Set as screen saver → Open screen saver settings**, then pick **Showcase**.
  (Or: Settings → Device Preferences → Screen saver → Screen saver → Showcase.)
- **Google TV / Fire TV** hide third-party screen savers. Set it once from a PC:
  ```
  adb shell settings put secure screensaver_components app.showcase.screensaver/.slideshow.ShowcaseDream
  ```
  To undo it, open Settings and choose a different screen saver.
- **Anywhere:** **Start slideshow now** plays it as a normal app. Any button exits.

## Good to know
- **"Original" image quality** downloads the artwork exactly as Plex stores it (4K where Plex has it). Most Android TV
  devices, the Shield included, draw apps at 1080p even on a 4K TV, so images are shown at the TV's app resolution.
  **Fit to screen** makes Plex shrink images first, which is faster on slow Wi-Fi.
- Artwork is cached on the device (up to 300 MB), so repeats don't hit your Plex server again.
- The library list is cached too, so the screen saver starts instantly and keeps working if Plex is briefly down.
- Your Plex token is stored privately on the device, never shown, and excluded from backups.
- Plex lists the app under **Settings → Authorized Devices** as "Showcase". Removing it there signs the TV out.

## Code map
| File | What it does |
|---|---|
| `plex/PlexClient.kt` | plex.tv PIN sign-in, server discovery, address testing, libraries, artwork URLs (port of `plexapi.py`) |
| `data/Prefs.kt` | All settings (the Android equivalent of `.env`) |
| `data/LibraryRepository.kt` | Cached list of artwork |
| `slideshow/SlideshowView.kt` | The slideshow itself (port of the web version's `showcase.js`) |
| `slideshow/ShowcaseDream.kt` | The system screen saver |
| `slideshow/SlideshowActivity.kt` | "Start slideshow now" |
| `ui/MainActivity.kt`, `ui/SetupViewModel.kt` | Setup screens: sign in, pick server, libraries, display, screen saver help |

## Before a Play Store release (later)
- Keep the name free of "Plex" (done: "Showcase"), and describe it as "works with Plex".
- `applicationId` (`app.showcase.screensaver`) can never change after the first upload, so decide on it first.
- Set up release signing, write a privacy policy (the app stores a Plex token on the device), and fill in the Data safety form.
- Weather comes from Open-Meteo, whose free API is for non-commercial use and needs attribution (shown on the
  Weather settings page). A free app with no ads is generally fine; if you ever charge or add ads, take their paid plan.
- New personal developer accounts need a 14-day closed test with at least 12 testers.

## Release builds and signing
Release APKs are signed with a private key that is **not** in this repo. The build looks for it in
`$SHOWCASE_SIGNING_DIR`, or `~/.showcase-signing/` by default, as `keystore.properties`:
```
storeFile=showcase-release.jks
storePassword=...
keyAlias=showcase
keyPassword=...
```
Then run `./gradlew assembleRelease`. Without that file, release builds are unsigned, and debug builds work as normal.

Android only installs an update if it's signed with the same key as the installed copy. So a debug build and a
release build can't replace each other: uninstall one before installing the other (you'll sign in again).
