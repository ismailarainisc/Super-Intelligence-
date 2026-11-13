#!/usr/bin/env bash
# Builds existing + RC8 instrumentation against the established SDK/manual pipeline.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"; : "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
export PATH="$JAVA_HOME/bin:$PATH"
tools="$ANDROID_HOME/build-tools/35.0.0"; android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
root=android/app/build/rc8-screen-tests
mkdir -p "$root/classes" "$root/dex" artifacts/android
find android/tests/java -name '*.java' -print > "$root/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$android_jar" -d "$root/classes" @"$root/sources.txt"
jar --create --file "$root/tests.jar" -C "$root/classes" .
"$tools/d8" --lib "$android_jar" --min-api 26 --output "$root/dex" "$root/tests.jar"
"$tools/aapt2" link --manifest android/tests/AndroidManifest.xml -I "$android_jar" -o "$root/unsigned.apk"
python3 - "$root" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 for p in (root/'dex').glob('*.dex'):z.write(p,p.name)
PY
"$tools/zipalign" -f -p 4 "$root/unsigned.apk" "$root/aligned.apk"
"$tools/apksigner" sign --ks android/local-debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out artifacts/android/MYRAA-Mobile-RC8-tests.apk "$root/aligned.apk"
"$tools/apksigner" verify --verbose artifacts/android/MYRAA-Mobile-RC8-tests.apk
