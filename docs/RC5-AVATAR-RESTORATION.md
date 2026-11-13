# Restore RC5's avatar and icon while keeping RC8

The original RC5 APK contains the Evelyn PMX model, texture mapping, nine texture images, and the original MYRAA icon. These files were absent from the original source ZIP and the public RC8 source-test APK. The recovery tool restores them locally without replacing RC8 application code.

## For the owner: restore from your original APK

Requires Python 3, in addition to the existing Node/JDK/Android SDK build prerequisites.

```bash
python scripts/restore-rc5-assets.py /path/to/MYRAA-Mobile-1.0.0-rc.5-private.apk
npm ci
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export MYRAA_REQUIRE_RC5_ASSETS=1
bash scripts/build-android.sh
```

Output: `artifacts/android/MYRAA-Mobile-1.0.0-rc.8-private-restored.apk`.

On Windows PowerShell, restore the assets with Python first, set `$env:MYRAA_REQUIRE_RC5_ASSETS = '1'`, set the SDK/JDK variables, then run `npm run build:android`. The restored output has the same `private-restored.apk` suffix.

The utility writes only ignored `assets/characters/evelyn/` and `assets/branding/myraa_icon.png`. It refuses to overwrite different existing owner assets. It does not extract credentials, settings or signing keys. A required restored build refuses missing models/textures/icon rather than silently substituting a neutral avatar. Source-only builds can still use the existing neutral fallback.

## Preserved

The PMX and all 11 character files are copied byte-for-byte from the original APK. The original icon is restored. RC8's existing avatar renderer/animation code, Live voice, task engine, native actions, Screen Companion and settings/history implementation are not rolled back by this restoration.

## Signing and distribution

An APK contains a public signing certificate, not the private signing key. The RC5 signing identity cannot be recovered by extracting its assets. To update a differently signed RC5 installation without uninstalling it, supply its original signing keystore to the build process. Do not delete the existing installation or its data merely to install a test APK.

The original project notices restrict the bundled model/artwork to private owner testing. Recovered assets and the restored private APK are therefore not committed to the public repository. The recovery tools and instructions are public; an owner build with the supplied assets is provided separately.

## Reproduce source/runtime checks

```bash
python tests/test_restore_rc5_assets.py
npm run lint
npm test
```

For real-model browser checks, restore the assets, run `node --import tsx scripts/qa-server.ts`, then run `tests/avatar-restoration-browser.cjs` with Playwright/Chromium available. It uses the real PMX, textures, Three.js rendering and mobile controls with read-only native UI defaults. It does not demonstrate Android/Gemini voice or physical-device compatibility. The optional full character matrix uses `MYRAA_FULL_AVATAR_QA=1` and is expensive with software WebGL.
