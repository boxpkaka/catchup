#!/usr/bin/env bash
# 不用 Gradle：直接用 SDK 自带工具把 src/ + res/ 打成签名 APK，输出 build/infofeed.apk
# 用法：./build.sh [版本号]，版本号即 versionName，App 内更新按它与 Release tag 比较；不传则为 0.0.0-dev
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk17}" PATH="$HOME/.local/share/jdk17/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
KS=release.keystore
PASS=$(grep '^APK_KEYSTORE_PASS=' ../.env | cut -d= -f2-)
VERSION_CODE=$(date +%y%m%d%H)
VERSION_NAME="${1:-0.0.0-dev}"

rm -rf build && mkdir -p build/gen build/classes build/dex

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias infofeed -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=infofeed" >/dev/null
fi

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 29 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o build/base.apk build/res.zip
javac --release 11 -classpath "$JAR" -encoding UTF-8 -d build/classes \
  $(find src build/gen -name '*.java') 2>build/javac.log || { cat build/javac.log; exit 1; }
"$BT/d8" --lib "$JAR" --min-api 29 --output build/dex $(find build/classes -name '*.class')
python3 -c "import shutil,zipfile;shutil.copy('build/base.apk','build/unsigned.apk');zipfile.ZipFile('build/unsigned.apk','a').write('build/dex/classes.dex','classes.dex')"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out build/infofeed.apk build/aligned.apk
echo "built build/infofeed.apk (v$VERSION_NAME, versionCode $VERSION_CODE)"
