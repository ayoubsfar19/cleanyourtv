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
```

The combined photos installer configures the companion screensaver and calls the Home preview installer when home-personnel-preview.apk is present. The Home installer saves the original HOME component in `private/home-before.json` before installation and preserves that first backup on every rerun. It installs or updates only the new personal launcher and opens it for preview; the default HOME stays unchanged. It also checks the companion recommendation bridge and saves its report in `private/recommendation-status.txt`. A companion probe failure is reported and the personal launcher is reopened. Both apps must be built with the same signing key for the protected bridge to work.

Optional local Wi-Fi SSID access can be granted with `-GrantLocation` on the Home installer. The combined installer passes that option for the requested Wi-Fi name. Android may still redact the name when its location services are off; no global location setting is changed. `-Apk '<path>'` selects another personal Home APK. A signature mismatch must be resolved with an APK signed by the same key; keep the installed app and its data.

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

See [the companion notes](../ambient/README-preview.md) for photo formats and remote controls. These Home scripts have not been run against a real TV during this preparation. Physical remote testing is still required before activation. ADB is left available during those tests; close debugging when testing is complete.

## Recommendation appearance and visibility

Version 3.1 shows only recommendations with successfully loaded artwork. Cards use rounded 16:9 pictures and the launcher focus effect; the selected title appears beside the section heading while the card is focused. Apps without readable artwork keep their app icon and show no recommendation shelf.

Hold OK on the header Settings button and use **Afficher les suggestions** beside **Module cards** to hide or show the shelf. This preference preserves the dock and other settings.

For ADB, use the shell-protected receiver (replace `<tvip>`):

```powershell
.\adb.exe -s '<tvip>:5555' shell am broadcast -n com.gathin.personalhome/com.gathin.tvhome.SuggestionsReceiver --ez enabled false
```

Use `true` instead of `false` to show suggestions again. The preference takes effect immediately while Home is open, and persists across restarts.
