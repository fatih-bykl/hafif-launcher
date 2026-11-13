#!/usr/bin/env bash
# Gradle'sız derleme: aapt2 + javac + d8 + apksigner
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
PLAT="$SDK/platforms/android-35/android.jar"
OUT=build
KS="$HOME/.android/hafif-launcher.keystore"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$PLAT" --manifest AndroidManifest.xml "$OUT/res.zip" \
  --min-sdk-version 21 --target-sdk-version 30 \
  --version-code 1 --version-name 1.0 -o "$OUT/base.apk"

javac -nowarn --release 8 -classpath "$PLAT" \
  -d "$OUT/classes" $(find src -name '*.java')

"$BT/d8" --release --min-api 21 --lib "$PLAT" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

python3 -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" "$OUT/base.apk" "$OUT/dex/classes.dex"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

# İmza anahtarı ve şifresi depoda değil, yalnızca bu bilgisayarda (~/.android) tutulur.
# İlk derlemede rastgele şifreyle otomatik oluşturulur.
KS_PASS_FILE="$KS.pass"
if [ ! -f "$KS" ]; then
  [ -f "$KS_PASS_FILE" ] || { umask 077; head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$KS_PASS_FILE"; }
  P="$(cat "$KS_PASS_FILE")"
  keytool -genkeypair -keystore "$KS" -alias hafif -storepass "$P" -keypass "$P" \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Hafif Launcher" >/dev/null 2>&1
fi
P="${KS_PASS:-$(cat "$KS_PASS_FILE")}"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$P" --key-pass "pass:$P" \
  --out HafifLauncher.apk "$OUT/aligned.apk"
rm -f HafifLauncher.apk.idsig

echo "Hazır: $(pwd)/HafifLauncher.apk ($(du -h HafifLauncher.apk | cut -f1))"
