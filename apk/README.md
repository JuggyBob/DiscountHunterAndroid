# Release APKs

Pre-built APKs for Discount Hunter, so you can install and try the app without a build environment.

| File | Version | Notes |
|------|---------|-------|
| [`release/app-release.apk`](release/app-release.apk) | 1.0.0 | Optimised release build |

## Installing

**These APKs are unsigned**, so Android will refuse them the first time. That is expected:

1. Tap the downloaded `.apk` in your file manager.
2. Android will say the file is from an unknown source. Tap **Settings** → allow your file manager
   to install apps.
3. Go back and tap the APK again.

To remove that permission afterwards: **Settings → Apps → (your file manager) → Permissions →
Install unknown apps → Deny**.

Requires **Android 7.0 (API 24)** or newer. Portrait orientation only.

## Verifying what you downloaded

The build is reproducible from the tagged source, so you can check that the APK matches the code:

```bash
git checkout v1.0.0
./gradlew assembleRelease
```

The resulting APK will not be byte-identical to the one here — Android build tools embed timestamps
and a build id — but its `versionName` and `versionCode` will match, and `aapt dump badging` on both
should agree.

## Building your own

Better if you want to change something, or you want a signed build for your own device:

```bash
git clone https://github.com/JuggyBob/DiscountHunterAndroid.git
cd DiscountHunterAndroid
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug     # debug build
./gradlew assembleRelease   # release build
```

For a signed release, see the commented signing steps in [`.github/workflows/release.yml`](../.github/workflows/release.yml).

## Safety

The app requests no dangerous permissions and sends nothing to a developer. See
[`SECURITY.md`](../SECURITY.md) for the threat model and how to report a vulnerability.

Game names, cover art and prices belong to their respective owners. Discount Hunter is not affiliated
with Valve, GOG.com or Epic Games — it displays data their public APIs return.
