# Photos & Football — preview

Independent Android TV app, package `com.gathin.ambienttv`, based on cleanyourtv's photo-screensaver. It does not replace or change the launcher. Personal images remain on the computer and TV.

The photo slideshow keeps a compact clock/date and weather. Clock, date and kickoff times follow the TV’s current timezone, including seasonal changes; no country timezone or extra hour is imposed. The bottom **Football** button opens a separate turquoise/purple sports page. La Liga fixtures, results and live scores come from ESPN. Score polling runs every 30 seconds only while the slideshow/screensaver is visible; goal banners last 10 seconds. The source can lag the live broadcast. New sessions establish a baseline and do not replay old goals. This preview has no cross-app overlay or background service.

## Install on Windows

Extract the download into `platform-tools`, beside `adb.exe`, with these files together:

- `photos-football-preview.apk`
- `install-preview.ps1`
- `sync-photos.ps1`
- `photos/` containing the existing pictures

Run from that folder in PowerShell (replace `<tv ip>`):

```powershell
powershell -ExecutionPolicy Bypass -File .\install-preview.ps1 -TvHost '<tv ip>'
```

The script validates pictures, connects to the TV, backs up the exact previous screensaver settings, and writes `restore-screensaver.ps1` **before any installation or setting change**. It installs the preview, copies pictures through the app's protected file provider, sets 30 seconds per photo and a 5-minute idle trigger, then opens the preview. Keep `screensaver-before.json` and the restore script. They contain the TV address and should remain private.

If the APK reports a signature mismatch, stop and report the error; do not uninstall or delete app data to work around it.

## Change pictures later

Put JPG, JPEG, PNG or WebP files in `photos/`, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\sync-photos.ps1 -TvHost '<tv ip>' -Replace
```

Up to 100 files, each between 1 byte and 10 MB, using ASCII letters/digits/dot/dash/underscore in filenames. `-Replace` removes stale files **only from this app's copy**, after all new pictures have been written successfully. It never changes the original `photos/` folder or another screensaver's files. Omit `-Replace` to keep extra pictures already in this preview. No APK rebuild or reinstall is needed.

## Remote

- On the pictures: **Right** shows the next photo, **Left** shows the previous one, wrapping at either end. Each manual change restarts the automatic photo interval. **OK** opens Football. Inside football the arrows remain normal navigation.
- On the sports page: arrows navigate, **OK** selects a match, **Back** returns to photos.
- From cleanyourtv Home: **Back** opens the photos immediately.
- From photos: **Back** exits the manual preview/dream; **Home** returns to the launcher.
- **Menu** opens the original photo settings in the manual preview.

The optional Back shortcut is enabled by this preview installer at your request: it filters Back only while `com.gathin.tvhome/.MainActivity` is the current window. It uses Android's key-filter accessibility interface, without access to window contents. Home and Back inside other apps remain normal. Existing enabled helpers are preserved, and their original settings are included in the restore backup.

## Restore

From `platform-tools`, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\restore-screensaver.ps1
```

The script restores the precise saved settings, deleting settings that originally had no value. It closes only this preview. It preserves photos, accounts, networking, ADB, all installed apps and launcher settings. It does not uninstall anything.

## Build

JDK 17, Android SDK platform 34/build-tools 34.0.0, from the repository root:

```sh
sh personal/ambient/gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

APK: `personal/ambient/app/build/outputs/apk/release/app-release.apk`. The generated download copies it as `photos-football-preview.apk`. This preview must still be checked on the real TV with its real remote.

## Expanded football centre (2.3)

Six pages: fixtures, live matches, results, standings, goalscorers and clubs, with LaLiga/Botola selection. OK on a match opens its available statistics, timeline, lineups and substitutes; club buttons open roster/profile/calendar details. Back goes to the preceding sports page, then photos. Other navigation and photo settings are retained.

Data adapters use ESPN for Spanish fixtures/details and Kooora for independent league data and Moroccan coverage. The latter also backs up the Spanish schedule if the first service fails. Provider identities stay internal and are not printed in sports footers or settings. There is no SofaScore integration or claim of unrestricted coverage. A competition page may expose only the current round; other dates explicitly fail instead of being reported as an empty calendar. Missing live/final scores are never replaced with 0–0.

Details are loaded on demand with a five-minute cache, separate from live polling. Stale cached data and connection errors are identified. No browsing, cookies, logins, personal-photo uploads or new background polling were added. The app remains an independent screensaver; the original launcher is unchanged.

## Photo navigation and orientation (2.4)

Right/Left on the photo layer move to the next/previous file in filename order and wrap at the ends. Manual changes reset the automatic interval. Rapid presses supersede stale decode/fade callbacks; arrows inside football retain their normal navigation. The loader reads EXIF orientation (including mirrored variants), transforms the decoded bitmap upright, then applies full-screen crop. Portrait photos are not rotated merely because they are tall. Original files are never rewritten.

## Large portrait photos (2.5)

Sampling now increases when the image would exceed the decode-pixel budget. A 12 MP portrait previously kept at sample=1 and rejected now uses sample=2, then fills the screen normally. A failed GPU copy falls back to the upright software bitmap. Original files are not rewritten; unreadable images are logged under AmbientPhotos for diagnosis.

## Android TV style and Home suggestions (3.0)

A subtle bottom-centre chevron replaces the Football button. Down opens sports directly; Up from the sports header returns to photos, with arrows inside tables unchanged. A local Wi-Fi label appears top-right; Android may redact the SSID when location access/services are unavailable, in which case it says Wi-Fi connecté. No global location/network setting is changed.

The separate Home preview (`com.gathin.personalhome`) reads app-published, searchable TV suggestions from this app's signature-protected provider. It does not receive arbitrary account histories. The companion requests READ_TV_LISTINGS, queries with no selection, and filters exact owner packages. Artwork is bounded and cached here; the launcher has no Internet permission. Private URIs and history are not logged or exported.

If Netflix shares no rows, a dated public global Top10 feed supplies text cards; these open Netflix itself and are labelled Top10 mondial. This is neither personal history nor a verified deep link to a title. Apps without accessible shared rows show an honest app-entry card. Kick follows and TizenTube continuation are not invented or substituted with another app's account.

The combined installer updates Photos, grants optional TV/Wi-Fi permissions, installs the separate Home preview and exports package counts only to `private/recommendation-status.txt`. Default Home is kept until `activate-personal-home.ps1` is run after physical testing. `restore-personal-home.ps1` restores the original Home component. Original launcher private settings are retained in the original app, not silently copied from inaccessible storage. The preview starts with the four dock apps from the provided screen: Netflix, TizenTube, Kick and TOD; other installed apps remain in the library/grid.

The optional TizenTube Night mode has its own [setup and remote controls](README-night.md). It streams the selected videos in the installed TizenTube app beneath the ambient widgets; no MP4 downloads are needed.
