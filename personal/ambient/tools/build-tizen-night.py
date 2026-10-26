#!/usr/bin/env python3
"""Build a separate TizenTube Nuit APK from the pinned public upstream release.

Python 3.9+, JDK 17, and Android build-tools 34.0.0 are required. This script
never connects to a TV, reads app data, or changes the normal TizenTube app.
Upstream: https://github.com/reisxd/TizenTubeCobalt/releases/tag/v1.0.8
Apktool: https://github.com/iBotPeaches/Apktool/releases/tag/v2.12.1
See README-tizen-night.md beside this script for credits and limitations.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


UPSTREAM_ID = "io.gh.reisxd.tizentube.cobalt"
NIGHT_ID = "com.gathin.tizennight"
LABEL = "TizenTube Nuit"
SOURCE_URL = "https://github.com/reisxd/TizenTubeCobalt/releases/download/v1.0.8/cobalt-arm.apk"
SOURCE_SHA256 = "59c7a8655424947cae7b4c29a65e723fb4ba6ddb45863473dc2a612470aa1e85"
TOOL_URL = "https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar"
TOOL_SHA256 = "66cf4524a4a45a7f56567d08b2c9b6ec237bcdd78cee69fd4a59c8a0243aeafa"
NOTICES = {
    "LICENSE-BSD.txt": ("LICENSE", "845022e0c1db1abb41a6ba4cd3c4b674ec290f3359d9d3c78ae558d4c0ed9308"),
    "LICENSE-Apache-2.0.txt": ("cobalt/LICENSE", "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
    "THIRD-PARTY-ANDROID.txt": ("cobalt/content/licenses/platform/android/licenses_cobalt.txt", "914c4bc6b7d16e4e9c60cab1b08f3f02077de9dee43f6a69f917d652fab39c7a"),
}
ANDROID_NS = "http://schemas.android.com/apk/res/android"
AND = "{" + ANDROID_NS + "}"


def sha256(path):
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
        return digest.hexdigest()


def fetch(url, path, expected):
    if not path.exists():
        request = urllib.request.Request(url, headers={"User-Agent": "TizenTube-Nuit-reproducible-build"})
        with urllib.request.urlopen(request) as source, path.open("wb") as target:
            shutil.copyfileobj(source, target)
    actual = sha256(path)
    if actual != expected:
        raise RuntimeError(f"SHA-256 mismatch for {path.name}: {actual}")


def run(command, env, log):
    with log.open("ab") as output:
        result = subprocess.run([str(arg) for arg in command], env=env,
                                stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"{Path(command[0]).name} failed; see {log}")


def patch(decoded):
    manifest = decoded / "AndroidManifest.xml"
    ET.register_namespace("android", ANDROID_NS)
    tree = ET.parse(manifest)
    root = tree.getroot()
    if root.get("package") != UPSTREAM_ID:
        raise RuntimeError("Unexpected upstream application ID")
    root.set("package", NIGHT_ID)
    if root.get(AND + "sharedUserId"):
        raise RuntimeError("Shared UID would break app-data isolation")
    for permission in list(root.findall("uses-permission")):
        if permission.get(AND + "name") in {"android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.GET_ACCOUNTS", "android.permission.RECORD_AUDIO", "com.google.android.gms.permission.AD_ID"}:
            root.remove(permission)
    application = root.find("application")
    application.set(AND + "label", LABEL)
    application.set(AND + "allowBackup", "false")
    application.set(AND + "fullBackupContent", "false")
    # Keep original class names even when a future input uses relative names.
    for node in application.iter():
        if node.tag in ("activity", "activity-alias", "service", "receiver", "provider", "application"):
            for attribute in ("name", "targetActivity", "appComponentFactory", "backupAgent"):
                name = node.get(AND + attribute)
                if name and (name.startswith(".") or "." not in name):
                    node.set(AND + attribute, UPSTREAM_ID + ("" if name.startswith(".") else ".") + name)
        for attribute in ("authorities", "permission", "readPermission", "writePermission"):
            value = node.get(AND + attribute)
            if value and UPSTREAM_ID in value:
                node.set(AND + attribute, value.replace(UPSTREAM_ID, NIGHT_ID))
    tree.write(manifest, encoding="utf-8", xml_declaration=True)
    for values in decoded.glob("res/values*/strings.xml"):
        text = values.read_text()
        text = re.sub(r'(<string name="app_name">)[^<]*(</string>)',
                      lambda match: match[1] + LABEL + match[2], text)
        values.write_text(text)
    bridge = decoded / "smali/dev/cobalt/coat/StarboardBridge.smali"
    text = bridge.read_text()
    pattern = r"\.method protected installAppFromURL\(Ljava/lang/String;\)Z\n.*?\.end method"
    replacement = """.method protected installAppFromURL(Ljava/lang/String;)Z
    .locals 1
    .annotation build Ldev/cobalt/util/UsedByNative;
    .end annotation

    const/4 v0, 0x0
    return v0
.end method"""
    text, count = re.subn(pattern, replacement, text, flags=re.S)
    if count != 1:
        raise RuntimeError("Expected exactly one upstream APK installer bridge")
    bridge.write_text(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=Path("/workspace/research/tizen-night"))
    parser.add_argument("--output", type=Path, default=Path("/workspace/output/tizentube-night.apk"))
    parser.add_argument("--jdk", type=Path, default=Path(os.environ.get("JAVA_HOME", "/workspace/jdk17")))
    parser.add_argument("--build-tools", type=Path,
                        default=Path("/workspace/android-sdk/build-tools/34.0.0"))
    parser.add_argument("--keystore", type=Path,
                        default=Path("/workspace/android-user/tizen-night.keystore"))
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.jdk))
    env["PATH"] = str(args.jdk / "bin") + os.pathsep + env["PATH"]
    source = args.work / "cobalt-arm.apk"
    apktool = args.work / "apktool.jar"
    fetch(SOURCE_URL, source, SOURCE_SHA256)
    fetch(TOOL_URL, apktool, TOOL_SHA256)
    license_dir = args.work / "licenses"
    license_dir.mkdir(exist_ok=True)
    for name, (relative_url, digest) in NOTICES.items():
        fetch("https://raw.githubusercontent.com/reisxd/TizenTubeCobalt/v1.0.8/" + relative_url,
              license_dir / name, digest)
    decoded = args.work / "decoded"
    java = args.jdk / "bin/java"
    framework = args.work / "framework"
    log = args.work / "build.log"
    log.write_text("")
    run([java, "-jar", apktool, "d", "-f", "-p", framework,
         source, "-o", decoded], env, log)
    patch(decoded)
    notices = decoded / "assets/night-notices"
    notices.mkdir(parents=True, exist_ok=True)
    for name in NOTICES:
        shutil.copyfile(license_dir / name, notices / name)
    (notices / "BUILD-CHANGES.txt").write_text(
        "TizenTube Nuit is a personal repackaging of TizenTubeCobalt v1.0.8 by reisxd and contributors.\n"
        "Source: https://github.com/reisxd/TizenTubeCobalt/tree/v1.0.8\n"
        "Modified 2026-10-26: separate application ID/providers, label, disable backup and APK installer, remove account/microphone/advertising permissions.\n"
        "Original native libraries and assets are unchanged. No account data is included.\n")
    unsigned = args.work / "night-unsigned.apk"
    aligned = args.work / "night-aligned.apk"
    run([java, "-jar", apktool, "b", "-p", framework, decoded,
         "-o", unsigned], env, log)
    run([args.build_tools / "zipalign", "-f", "-p", "4", unsigned, aligned], env, log)
    args.keystore.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    password_file = args.keystore.with_suffix(args.keystore.suffix + ".pass")
    if not args.keystore.exists():
        if password_file.exists():
            raise RuntimeError("Password exists without its keystore; restore the matching key")
        descriptor = os.open(password_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write(secrets.token_urlsafe(36) + "\n")
        run([args.jdk / "bin/keytool", "-genkeypair", "-noprompt", "-keystore", args.keystore,
             "-alias", "tizen-night", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-dname", "CN=TizenTube Nuit Personal Build",
             "-storepass:file", password_file, "-keypass:file", password_file], env, log)
        args.keystore.chmod(0o600)
    if not password_file.exists():
        raise RuntimeError("Keystore password file is missing; refusing to replace the key")
    run([args.build_tools / "apksigner", "sign", "--ks", args.keystore,
         "--ks-key-alias", "tizen-night", "--ks-pass", "file:" + str(password_file),
         "--v4-signing-enabled", "false",
         "--out", args.output, aligned], env, log)
    run([args.build_tools / "apksigner", "verify", "--verbose", "--print-certs", args.output], env, log)
    run([args.build_tools / "zipalign", "-c", "-p", "4", args.output], env, log)
    badging = subprocess.check_output([str(args.build_tools / "aapt"), "dump", "badging", str(args.output)],
                                      env=env, text=True)
    if f"package: name='{NIGHT_ID}'" not in badging or "native-code: 'armeabi-v7a'" not in badging:
        raise RuntimeError("Output package/ABI verification failed")
    if "sdkVersion:'24'" not in badging or f"application-label:'{LABEL}'" not in badging:
        raise RuntimeError("Output minSdk/label verification failed")
    manifest_dump = subprocess.check_output(
        [str(args.build_tools / "aapt"), "dump", "xmltree", str(args.output), "AndroidManifest.xml"],
        env=env, text=True)
    if (UPSTREAM_ID in manifest_dump or "REQUEST_INSTALL_PACKAGES" in manifest_dump or "GET_ACCOUNTS" in manifest_dump
            or "RECORD_AUDIO" in manifest_dump or "com.google.android.gms.permission.AD_ID" in manifest_dump
            or NIGHT_ID + ".fileprovider" not in manifest_dump
            or NIGHT_ID + ".androidx-startup" not in manifest_dump):
        raise RuntimeError("Output manifest isolation/updater permission verification failed")
    args.output.with_suffix(".manifest.txt").write_text(manifest_dump)
    # The native engine and every existing non-resource asset must be identical.
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(args.output) as rebuilt:
        preserved = [name for name in original.namelist() if name.startswith(("lib/", "assets/")) and not name.endswith("/")]
        for name in preserved:
            if original.read(name) != rebuilt.read(name):
                raise RuntimeError(f"Upstream native library or asset changed: {name}")
    metadata = {
        "applicationId": NIGHT_ID, "label": LABEL, "versionName": "1.0.8", "versionCode": 108,
        "minSdk": 24, "targetSdk": 34, "abi": "armeabi-v7a",
        "sourceUrl": SOURCE_URL, "sourceSha256": SOURCE_SHA256,
        "apktoolUrl": TOOL_URL, "apktoolSha256": TOOL_SHA256,
        "apkSha256": sha256(args.output), "apkBytes": args.output.stat().st_size,
        "changes": ["Separate application ID and provider authorities", "TizenTube Nuit label", "Remove account-list, microphone and advertising-ID permissions",
                    "Disable Android backup/restore", "Disable APK installer bridge and remove REQUEST_INSTALL_PACKAGES"],
        "nativeAndAssetEntriesVerifiedUnchanged": len(preserved),
        "licenseAssetsAdded": list(NOTICES) + ["BUILD-CHANGES.txt"],
        "userdataCopied": False, "deviceRuntimeTested": False,
        "licenseAndSource": "https://github.com/reisxd/TizenTubeCobalt/tree/v1.0.8",
    }
    args.output.with_suffix(".json").write_text(json.dumps(metadata, indent=2) + "\n")
    args.output.with_suffix(".sha256").write_text(metadata["apkSha256"] + "  " + args.output.name + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
