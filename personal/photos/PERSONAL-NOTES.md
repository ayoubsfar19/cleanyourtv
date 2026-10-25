# Photos personnelles — development version

Derived from CleanYourTV's MIT-licensed photo screensaver at commit 9204f65e8b93f9ddff1afcdcb8477397834154c9. The inherited README describes the upstream features; these notes describe the custom version.

This independent app uses package `com.gathin.personalphotos`, so installing it does not replace the original photo screensaver or launcher. The slideshow displays local pictures, defaulting to 30 seconds per picture. A new overlay shows a French clock/date and weather for Oujda and Salé, refreshed every 15 minutes while visible. Each reading includes its model time. Failed fetches explicitly mark cached readings as retained data; without a cache, the city says weather is unavailable.

## Current limits

Version 1.2 layout: weather at the upper left, a seven-row football table at the lower left, and a large clock/date at the lower right. Translucent gradient panels and club-colour markers keep the centre of the photograph visible. The table shows the followed club's score first, then the opponent's score, whether the club is home or away. Live rows use green only while tracking is enabled and the cached reading is fresh. Real-device screenshot verification is still required.

Version 1.3 adds fifteen-second automatic pages within that same bottom-left table: followed-club results; complete Spanish standings (20 teams in the checked source); Spanish top ten scorers; complete Moroccan standings (16 teams); Moroccan top ten scorers. Seven rows fit on each page; every row is retained across pages. League data is fetched at most once every ten minutes while enabled tracking is running and the TV is awake. Goal monitoring continues independently of the page on screen. These are league leaders, not individual match goalscorer lists. League and player names not mapped to French remain as supplied by the Arabic source. Tests cover both real league snapshots, tied scorer rankings, wrong-competition rejection and missing-season rejection.

- Seven football clubs are supported through the public Kooora team pages: FC Barcelona, Real Madrid, Atlético Madrid, Real Betis, Raja Casablanca, Wydad Casablanca and AS FAR. The source's embedded match data was fetched for all seven. The latest completed match remains visible until a valid live match is returned. A fixture is used only when no completed match exists.
- Enable tracking explicitly in the Football settings. It runs as a foreground service while the TV is awake, checking each team and waiting 60 seconds after the batch. Source caching, network time and polling add delay. This is a public website adapter, not an officially supported Kooora API; format changes or blocking may interrupt updates. Cached/offline readings are labelled, not presented as fresh live scores.
- Goals scored by a watched club trigger a ten-second overlay; only Barcelona goals have a notification tone. Overlay permission must be granted. No accessibility or key-filtering service is installed. Initial loads, repeated snapshots and downward score corrections do not alert. The maximum-score rule suppresses duplicate alerts after a correction but can miss a later goal returning to the same score; event-by-event goal IDs are not available from this adapter.
- A clearly marked TEST banner is available in Football settings. A real live goal and overlays over each streaming app still require device verification. Some video apps/firmware can hide overlays.
- Persistent scores on the launcher Home screen are not implemented; scores are displayed on the photo slideshow, with temporary goal overlays across apps. The module card opens the slideshow.
- Five-minute idle activation requires a separate, reversible Android settings change after device verification. Installing this APK alone does not change the system screensaver.
- Compilation, lint and unit tests do not establish real-device visual correctness.

## Data

The custom weather overlay sends two fixed city coordinates to Open-Meteo over HTTPS. The foreground football service requests seven public team pages on Kooora and stores score snapshots locally. Neither uploads pictures or uses the TV's location. The inherited optional weather feature retains its original coarse-location permission, off by default. Photos remain in this app's files directory. ADB writes them through `content://com.gathin.personalphotos.files/`, protected by `WRITE_SECURE_SETTINGS`. Boot permission resumes explicitly enabled tracking; the overlay permission displays goal banners over other apps.

## Reproduce

Keep this folder in the repository. Use JDK 17, Android SDK 34 and build-tools 34.0.0:

```sh
personal/photos/gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

Source archives exclude signing keys. Preserve the build's signing key privately for future updates. Another signing key cannot replace an installed APK with the same package name.
