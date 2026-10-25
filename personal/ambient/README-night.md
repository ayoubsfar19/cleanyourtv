# Native Night videos

Night has four slots in the selected order. Original videos are **not included**. Supply your original MP4 files, or direct HTTPS URLs that serve those MP4 files. A YouTube watch page is not a native video source; this helper does not download or extract YouTube videos.

| Slot/file | Selected video ID | Label |
|---|---|---|
| `1.mp4` | `7_Wbmy79wxQ` | The Art Corner 1 |
| `2.mp4` | `BVdHrTfO2PE` | The Art Corner 2 |
| `3.mp4` | `yTRHheKoVhY` | Bedroom R&B 1 |
| `4.mp4` | `BUeCNBg_qYk` | Bedroom R&B 2 |

Install the updated preview APK first. Put `sync-night-videos.ps1` beside `adb.exe` in `platform-tools`, create a `night-videos` folder beside the script, and put the chosen files there using the names above. Each file must be nonempty and at most 1 GiB. Files are copied unchanged: no conversion, trimming or audio replacement. Choose formats your TV can decode, such as an original H.264/AAC MP4.

From PowerShell in `platform-tools`, replace `<tv ip>` with the TV address:

```powershell
.\adb.exe connect '<tv ip>:5555'
powershell -ExecutionPolicy Bypass -File .\sync-night-videos.ps1 -TvHost '<tv ip>'
```

Use `-Folder 'C:\path\to\night-videos'` for another source folder. Add `-ValidateOnly` to check files and configuration without contacting the TV. The script updates only the supplied numbered videos and Night configuration; it never deletes absent videos, modifies original files or photos, changes the launcher, or selects a screensaver. Reopen the preview and choose a Night video after syncing. Missing sources are shown as unavailable until supplied.

For streaming, create `night-videos/night-videos.json` using this shape and replace each empty `url` with your direct HTTPS MP4 URL. Keep empty URLs for local files. The optional file accepts 1–4 unique IDs from the table; preserve each ID's numbered filename. All four slots remain visible: omitted IDs use their default numbered local file. A valid local file on the TV takes priority over its URL.

```json
{
  "version": 1,
  "videos": [
    { "id": "7_Wbmy79wxQ", "title": "The Art Corner 1", "file": "1.mp4", "url": "" },
    { "id": "BVdHrTfO2PE", "title": "The Art Corner 2", "file": "2.mp4", "url": "" },
    { "id": "yTRHheKoVhY", "title": "Bedroom R&B 1", "file": "3.mp4", "url": "" },
    { "id": "BUeCNBg_qYk", "title": "Bedroom R&B 2", "file": "4.mp4", "url": "" }
  ]
}
```

Then run the same sync command. A configuration-only sync works without local MP4 files. Configuration is limited to 65536 bytes, titles to 100 characters without control characters, and URLs to 4096 characters without embedded credentials. Direct URLs are streamed by the TV; the script does not download them or test remote playback. Use a URL the TV can access without browser sign-in. Streaming avoids storing the whole video on the TV; large local uploads need free TV storage for both staging and the app copy. Private URLs remain in the local configuration and the app's Night configuration, so keep those files private.

## Remote controls

On the photo screensaver, Left/Right keep changing photos. Press Up to select the small moon, then OK to enter Night mode. The clock/date, weather and Wi-Fi stay visible over the video.

In Night mode, OK or a direction reveals the controls. **Playlist** opens the four choices; selecting one closes the list immediately. **Lumière tamisée / Lumière normale** toggles a dark overlay over the video without changing TV-wide picture settings. Controls hide after five seconds. **Photos** or Back restores the slideshow; when the playlist is open, Back closes the playlist first.

Playback repeats the selected file. It uses the original stream with no re-encoding or replacement audio, and a low player-local audio level; the system volume is not changed. Leaving Night, Home, or stopping the screensaver releases playback and audio focus. A missing file or incompatible codec displays a recoverable message with Back to photos.

The native player requests the source video resolution on its own SurfaceView layer beneath the widgets. Actual 4K output depends on a genuine 4K source, the TV decoder, display mode and compositor. This build has not been verified on the owner's physical TV and does not claim measured 4K output. The supplied YouTube watch links have not been downloaded: direct access to them was blocked in the preparation environment.
