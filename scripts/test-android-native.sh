#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 35}"
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
cache=.myraa-data/native-test-deps
mkdir -p "$cache/classes"
json_jar="${JSON_JAR:-$cache/json-20240303.jar}"
if [[ ! -f "$json_jar" ]]; then
  curl -fL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$json_jar"
fi
python3 - "$json_jar" <<'PYCODE'
import hashlib,sys
assert hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()=='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed','Unexpected JSON test dependency checksum'
PYCODE
javac -cp "$json_jar:$android_jar" -d "$cache/classes" android/app/src/main/java/com/myraa/mobile/MobileActionSpec.java android/app/src/main/java/com/myraa/mobile/MobileTaskCoordinator.java android/app/src/main/java/com/myraa/mobile/WakeListener.java tests/native/MobileTaskCoordinatorTest.java
java -ea -cp "$json_jar:$android_jar:$cache/classes" MobileTaskCoordinatorTest
