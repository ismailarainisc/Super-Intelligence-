#!/usr/bin/env bash
# Linux/macOS source build; optional owner-provided character assets stay private.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform/build-tools 35}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}"
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
build_tools="$ANDROID_HOME/build-tools/35.0.0"
build_root="android/app/build/rc8"
asset_root="android/app/src/main/assets"
variant=source-test
if [[ -f assets/characters/evelyn/model.pmx ]]; then variant=private-restored; fi
if [[ "${MYRAA_REQUIRE_RC5_ASSETS:-0}" == 1 && "$variant" != private-restored ]]; then
  echo "Restored RC5 assets are required. Run scripts/restore-rc5-assets.py with your original APK." >&2; exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
node scripts/validate-mobile-assets.cjs
npm exec -- vite build --config vite.mobile.config.ts
if [[ -d assets/characters/evelyn ]]; then
  mkdir -p "$asset_root/characters"
  cp -R assets/characters/evelyn "$asset_root/characters/"
fi
node --import tsx scripts/export-mobile-personality.ts
mkdir -p "$build_root/classes" "$build_root/dex" "$build_root/res/drawable" artifacts/android
python3 - "$build_root/res/drawable" <<'CLEAN'
from pathlib import Path
import sys
for name in ['myraa_icon.xml','myraa_icon.png']:
    path=Path(sys.argv[1])/name
    if path.exists():path.unlink()
CLEAN
if [[ -f assets/branding/myraa_icon.png ]]; then
  cp assets/branding/myraa_icon.png "$build_root/res/drawable/myraa_icon.png"
else
cat > "$build_root/res/drawable/myraa_icon.xml" <<'ICON'
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48"><path android:fillColor="#17252a" android:pathData="M0,0h48v48H0z"/><path android:fillColor="#b7d9d2" android:pathData="M10,36V12h5l9,14 9,-14h5v24h-5V21l-9,13 -9,-13v15z"/></vector>
ICON
fi
find android/app/src/main/java -name '*.java' -print > "$build_root/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$android_jar" -d "$build_root/classes" @"$build_root/sources.txt"
jar --create --file "$build_root/classes.jar" -C "$build_root/classes" .
"$build_tools/d8" --lib "$android_jar" --min-api 26 --output "$build_root/dex" "$build_root/classes.jar"
"$build_tools/aapt2" compile --dir "$build_root/res" -o "$build_root/resources.zip"
"$build_tools/aapt2" link --manifest android/app/src/main/AndroidManifest.xml -I "$android_jar" -A "$asset_root" --min-sdk-version 26 --target-sdk-version 35 -o "$build_root/unsigned.apk" "$build_root/resources.zip"
python3 - "$build_root" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    for dex in (root/'dex').glob('*.dex'): z.write(dex,dex.name)
PY
"$build_tools/zipalign" -f -p 4 "$build_root/unsigned.apk" "$build_root/aligned.apk"
if [[ ! -f android/local-debug.keystore ]]; then
  keytool -genkeypair -keystore android/local-debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=MYRAA Local Development,O=MYRAA,C=IN'
fi
apk="artifacts/android/MYRAA-Mobile-1.0.0-rc.8-$variant.apk"
"$build_tools/apksigner" sign --ks android/local-debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$apk" "$build_root/aligned.apk"
"$build_tools/apksigner" verify --verbose "$apk"
sha256sum "$apk"
