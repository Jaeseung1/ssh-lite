#!/bin/bash
# 폰(안드로이드) 앱 SSH-Lite.apk 만들기. Gradle·안드로이드 스튜디오 없이 기본 도구만 씀.
# 필요: JDK 17+, JDK 8(람다 변환용), python3, aapt, dalvik-exchange(dx), zipalign, apksigner, android.jar
#   (Ubuntu/Debian: sudo apt install openjdk-8-jdk-headless aapt dalvik-exchange zipalign apksigner android-sdk-platform-23)
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
JAVA8=${JAVA8:-/usr/lib/jvm/java-8-openjdk-amd64/bin/java}
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/assets/ui" "$OUT/classes" "$OUT/jsch" "$OUT/all" "$OUT/dexin"

# 1. 화면: PC 앱과 같은 ui 폴더 + 서버 명령·셸 설정 (ssh_lite.pyw 에서 꺼냄)
cp -r ../ui/. "$OUT/assets/ui/"
python3 gen_shared.py "$OUT/assets/shared.json"
mkdir -p "$OUT/assets/licenses" && cp ../LICENSE ../licenses/JSch.txt ../licenses/BouncyCastle.txt "$OUT/assets/licenses/"  # 앱 안에도 라이선스 글

# 2. SSH 라이브러리: JSch 에서 Java 9+ 전용 부분(META-INF/versions)은 빼야 폰에서 돌아감
(cd "$OUT/jsch" && unzip -q ../../libs/jsch-*.jar -x 'META-INF/*')
jar cf "$OUT/jsch8.jar" -C "$OUT/jsch" .

# 3. 컴파일 (Java 8 문법 + 안드로이드 API)
javac --release 8 -encoding UTF-8 -Xlint:-options -nowarn \
  -cp "$ANDROID_JAR:$OUT/jsch8.jar:libs/bc-subset.jar" -d "$OUT/classes" $(find src -name '*.java')

# 4. 람다 -> 보통 클래스로 바꾸기 (안드로이드에는 람다를 실행하는 LambdaMetafactory 가 없어서 그대로 두면 켜자마자 멈춤)
cp -r "$OUT/classes/." "$OUT/all/"
(cd "$OUT/all" && unzip -qo ../jsch8.jar && unzip -qo ../../libs/bc-subset.jar && rm -rf META-INF)
"$JAVA8" -Dretrolambda.inputDir="$OUT/all" -Dretrolambda.outputDir="$OUT/dexin" -Dretrolambda.bytecodeVersion=52 \
  -Dretrolambda.classpath="$OUT/all:$ANDROID_JAR" -javaagent:tools/retrolambda-2.5.7.jar -jar tools/retrolambda-2.5.7.jar > "$OUT/retrolambda.log"

# 5. 폰에 없는 기능을 쓰는지 확인 (람다가 남았거나 없는 메서드를 부르면 여기서 멈춤)
python3 check_api.py "$OUT/dexin" "$ANDROID_JAR"

# 6. 안드로이드 실행 파일(dex)로 바꾸기
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/dexin"

# 7. 묶기 -> 정렬 -> 서명 (같은 키로 서명해야 폰에서 '업데이트'로 설치됨)
aapt package -f -M AndroidManifest.xml -S res -A "$OUT/assets" -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && zip -q unsigned.apk classes.dex)
python3 patch_manifest.py "$OUT/unsigned.apk"  # aapt 가 모르는 최신 속성(포그라운드 서비스 종류) 넣기
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f release.keystore ]; then
  keytool -genkeypair -keystore release.keystore -alias sshlite -keyalg RSA -keysize 3072 -validity 36500 \
    -dname "CN=SSH Lite, O=Jaeseung1" -storepass sshlite -keypass sshlite
fi
apksigner sign --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --ks release.keystore --ks-pass pass:sshlite --key-pass pass:sshlite --out SSH-Lite.apk "$OUT/aligned.apk"
apksigner verify SSH-Lite.apk
echo "만들었어요: $(pwd)/SSH-Lite.apk"

# 8. 개인용: ../defaults.local.json(내 서버 정보)이 있으면 그걸 넣은 SSH-Lite-personal.apk 도 만듦 (저장소에는 안 올라감)
if [ -f ../defaults.local.json ]; then
  cp "$OUT/unsigned.apk" "$OUT/personal.apk"
  mkdir -p "$OUT/personal/assets" && cp ../defaults.local.json "$OUT/personal/assets/"
  (cd "$OUT/personal" && zip -q ../personal.apk assets/defaults.local.json)
  zipalign -f 4 "$OUT/personal.apk" "$OUT/personal-aligned.apk"
  apksigner sign --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --ks release.keystore --ks-pass pass:sshlite --key-pass pass:sshlite --out SSH-Lite-personal.apk "$OUT/personal-aligned.apk"
  apksigner verify SSH-Lite-personal.apk
  echo "개인용도 만들었어요: $(pwd)/SSH-Lite-personal.apk"
fi
