# Personal Home preview

This personal fork installs `com.gathin.personalhome` alongside the existing `com.gathin.tvhome`. The old launcher, its private settings and its theme remain in place. The preview uses a four-column layout with Netflix, TizenTube, Kick and TOD first; the other available apps stay in the grid. Existing private settings and wallpaper files are not automatically migrated, so the preview is not a promise of an exact copy of the old theme.

Extract the preview bundle beside `adb.exe` in Windows platform-tools. Keep these files together:

- `home-personnel-preview.apk`
- `install-home-preview.ps1`, `activate-personal-home.ps1`, `restore-personal-home.ps1`
- `photos-football-preview.apk`, `install-preview.ps1`, `sync-photos.ps1`
- Your existing local `photos/` folder

Use Windows PowerShell 5.1 or later. Replace `<tvip>` with the TV address, authorize the debugging prompt on the TV, and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\install-preview.ps1 -TvHost '<tvip>'
powershell -ExecutionPolicy Bypass -File .\install-home-preview.ps1 -TvHost '<tvip>'
```

The photos installer configures the companion screensaver. The Home installer saves the original HOME component in `private/home-before.json` before installation and preserves that first backup on every rerun. It installs or updates only the new personal launcher and opens it for preview; the default HOME stays unchanged. It also checks the companion recommendation bridge and saves its report in `private/recommendation-status.txt`. A companion probe failure is reported and the personal launcher is reopened. Both apps must be built with the same signing key for the protected bridge to work.

Optional weather location access can be granted with `-GrantLocation` on the Home installer. Without it, no location permission is granted by that script. `-Apk '<path>'` selects another personal Home APK. A signature mismatch must be resolved with an APK signed by the same key; keep the installed app and its data.

Physically test navigation, app launching, recommendations and the real remote in the preview. Once it behaves correctly, activate it separately:

```powershell
powershell -ExecutionPolicy Bypass -File .\activate-personal-home.ps1 -TvHost '<tvip>'
```

Activation confirms the new launcher is installed, enabled and in the foreground before setting it as HOME, then verifies the resolved HOME component. It leaves the original and system launchers installed and enabled as before. It does not disable Google TV or setup services.

Restore the exact first saved HOME component at any time:

```powershell
powershell -ExecutionPolicy Bypass -File .\restore-personal-home.ps1
```

Restore sets the saved HOME and opens it. It keeps both launchers, their data and the companion app. The Home backup belongs to one TV; use a separate bundle folder for another TV. Keep `private/`, all backup JSON files, restore files containing personal settings, photos and probe reports local; they can contain the TV address or other private information and must not be published. Keep the companion's `screensaver-before.json` and generated `restore-screensaver.ps1` to undo its settings separately.

To update pictures later, edit the local `photos/` folder and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\sync-photos.ps1 -TvHost '<tvip>' -Replace
```

See [the companion notes](personal/ambient/README-preview.md) for photo formats and remote controls. These Home scripts have not been run against a real TV during this preparation. Physical remote testing is still required before activation. ADB is left available during those tests; close debugging when testing is complete.
