# TizenTube Nuit build

`build-tizen-night.py` produces `com.gathin.tizennight` / **TizenTube Nuit**, an isolated
personal copy of [TizenTubeCobalt v1.0.8](https://github.com/reisxd/TizenTubeCobalt/releases/tag/v1.0.8).
Its package, signing key, provider authorities and Android data directory are separate
from the normal `io.gh.reisxd.tizentube.cobalt` installation. No app data is read or copied.
Android backup/restore is disabled for the clone. Choose Guest on first launch and keep
this copy signed out; the normal TizenTube account is unaffected.

The pinned ARM release uses `armeabi-v7a`, minSdk 24 and targetSdk 34. It can be installed
on Android TV 11 / API 30 ARM32. Device playback and remote behavior require a real TV
test. Compilation and APK checks alone do not establish those runtime behaviors.

Run with Python 3, JDK 17 and Android build-tools 34.0.0:

```sh
python3 personal/ambient/tools/build-tizen-night.py \
  --jdk /workspace/jdk17 \
  --build-tools /workspace/android-sdk/build-tools/34.0.0 \
  --work /workspace/research/tizen-night \
  --keystore /workspace/android-user/tizen-night.keystore \
  --output /workspace/output/tizentube-night.apk
```

The script downloads the official release and Apktool 2.12.1 using pinned SHA-256 hashes,
patches the manifest and installer bridge, rebuilds, aligns and signs the APK. The stable
private signing key and password file are created with private permissions outside the
source tree. Keep them private and backed up to sign future updates. A new builder can
create their own key; that produces a different signature. Do not include any keystore
or password in a source archive or artifact upload.

The change to `StarboardBridge.installAppFromURL(String)` returns `false` without
downloading or installing an APK, and `REQUEST_INSTALL_PACKAGES` is removed. The dedicated copy also omits `GET_ACCOUNTS`, `RECORD_AUDIO` and the Google advertising-ID permission; it does not need those for signed-out night playback. There is
no separate Java update-check/prompt method in the pinned input. The upstream native
engine loads remote TizenTube JavaScript, so an upstream update message could still
appear; its APK installation bridge remains disabled in this copy.

The script verifies the package/label/minSdk/ABI, two isolated provider authorities,
absence of the old package and installer permission, signature, zip alignment, and
byte identity of every original native library and asset. It writes a public JSON
build record, manifest dump and APK SHA-256 beside the output. It never installs the APK
or connects to a TV.

## Credits and notices

TizenTubeCobalt is by **reisxd and contributors**, based on Chromium/Cobalt and its
third-party components. This personal package retains every original native library
and asset and adds exact upstream license files under `assets/night-notices/`:

- [Repository BSD 3-clause license, Chromium Authors](https://github.com/reisxd/TizenTubeCobalt/blob/v1.0.8/LICENSE).
- [Cobalt Apache License 2.0](https://github.com/reisxd/TizenTubeCobalt/blob/v1.0.8/cobalt/LICENSE).
- [Android third-party notices](https://github.com/reisxd/TizenTubeCobalt/blob/v1.0.8/cobalt/content/licenses/platform/android/licenses_cobalt.txt).

The APK includes a short `BUILD-CHANGES.txt` naming these packaging changes. The upstream
source and notice URLs are pinned to `v1.0.8`; hashes are in the build script. The official
source APK SHA-256 is
`59c7a8655424947cae7b4c29a65e723fb4ba6ddb45863473dc2a612470aa1e85`.
Apktool is a build tool, not bundled in the APK; its [source and license](https://github.com/iBotPeaches/Apktool)
are published by its authors.
