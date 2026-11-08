#!/bin/bash
# 水衣管家 - 极简 Android 构建脚本 (无 gradle, 无 Android Studio)
#
# 用法:
#   KS_PASS=<签名口令> ANDROID_BUILD_TOOLS=... ANDROID_PLATFORM=... ./build.sh
#   ./build.sh output.apk        指定输出文件名(默认 build/hz-water.apk)
#
# 需要自备的三个外部件(本仓库不含, 体积大且需与自己的 SDK 版本匹配):
#   ANDROID_BUILD_TOOLS  build-tools 目录, 例如 $ANDROID_HOME/build-tools/34.0.0
#   ANDROID_PLATFORM     android.jar, 例如 $ANDROID_HOME/platforms/android-34/android.jar
#   libs/                支付宝 SDK 的 class(解压 alipaysdk.aar 里的 classes.jar)
#                        与 zxing-core.jar(扫二维码解码用)
#
# 签名:
#   首次运行在仓库根目录生成 keystore.jks, 口令取 KS_PASS; 未设置则随机生成并打印一次.
#   keystore.jks 不纳入版本管理. 一旦丢失, 后续版本无法覆盖安装已发布的旧包.
#
# 三个不可绕过的约束:
#   1. aapt2 打不开含中文的绝对路径 -> 先 cd 到脚本所在目录, 全程相对路径;
#   2. javac 21 生成的匿名内部类(带 NestHost)会让 build-tools 34 的 D8 8.2.2 抛 NPE
#      -> 源码禁用匿名内部类, 只用静态嵌套类或顶层类;
#   3. d8 的 --output 目录必须事先存在, 否则报 "Invalid output".
set -e

cd "$(dirname "$0")"
ROOT="$(pwd)"

BT="${ANDROID_BUILD_TOOLS:?请设置 ANDROID_BUILD_TOOLS 指向 build-tools 目录}"
PLAT="${ANDROID_PLATFORM:?请设置 ANDROID_PLATFORM 指向 android.jar}"
APP="app"
OUT="build"
APKNAME="${1:-hz-water.apk}"
KS="keystore.jks"

if [ ! -f "libs/alipay_x/com/alipay/sdk/app/PayTask.class" ]; then
  echo "缺少 libs/alipay_x. 请解压 alipaysdk.aar 内的 classes.jar 到 libs/alipay_x/." >&2
  exit 1
fi
if [ ! -f "libs/zxing-core.jar" ]; then
  echo "缺少 libs/zxing-core.jar(二维码解码依赖)." >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT" \
  --min-sdk-version 23 \
  --target-sdk-version 33 \
  --version-code 1 --version-name 1.0 \
  -A "$APP/assets" \
  --auto-add-overlay

echo "[3/6] javac"
find "$APP/src" -name '*.java' > "$OUT/srcs.txt"
find "$OUT" -name 'R.java' >> "$OUT/srcs.txt"
JLOG="$OUT/javac.log"
if ! javac -encoding UTF-8 --release 8 \
    -classpath "$PLAT;libs/zxing-core.jar;libs/alipay_x/classes.jar" \
    -d "$OUT/classes" @"$OUT/srcs.txt" > "$JLOG" 2>&1; then
  grep -E "错误|error" "$JLOG" | head -40
  echo "javac 失败, 详见 $JLOG"
  exit 1
fi
grep -vE "^(注意|注|Note|警告|warning)" "$JLOG" | head -20 || true

# zxing 是 Java 8 预编译 jar, 解进同一目录让 d8 一并 dex 化
if (cd "$OUT/classes" && jar xf "$ROOT/libs/zxing-core.jar") 2>/dev/null; then
  echo "  zxing classes merged"
elif (cd "$OUT/classes" && unzip -oq "$ROOT/libs/zxing-core.jar") 2>/dev/null; then
  echo "  zxing classes merged (unzip)"
fi
if [ -d libs/alipay_x/com ]; then
  cp -r libs/alipay_x/com "$OUT/classes/" 2>/dev/null || true
  echo "  alipay classes merged"
fi

echo "[4/6] d8"
# 类太多时命令行会超长(支付宝 SDK 有 700 多个类), 先打成 jar 再喂 d8
KJAR="$OUT/classes.jar"
PYEXE="${PYEXE:-python3}"
command -v "$PYEXE" >/dev/null 2>&1 || PYEXE=python
"$PYEXE" - "$OUT/classes" "$KJAR" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
n = 0
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _dirs, files in os.walk(src):
        for f in files:
            if f.endswith('.class'):
                p = os.path.join(base, f)
                z.write(p, os.path.relpath(p, src))
                n += 1
print('  packed %d classes -> %s' % (n, out))
PY

java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLAT" --min-api 23 --release \
  --output "$OUT/dex" "$KJAR"

echo "[5/6] 合入 dex"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# aapt2 只写资源与 assets, 不写 classes.dex; 用 zipfile 追加(APK 允许重复条目)
"$PYEXE" - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('  appended classes.dex ->', apk)
PY

echo "[6/6] zipalign + apksigner"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# 签名口令: KS_PASS 优先; 首次没有就随机生成并打印一次, 之后必须一直用同一个
if [ ! -f "$KS" ]; then
  if [ -z "$KS_PASS" ]; then
    KS_PASS="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    echo "  未设置 KS_PASS, 已生成随机口令: $KS_PASS"
    echo "  请立即保存; 后续构建用 KS_PASS=$KS_PASS ./build.sh"
  fi
  echo "  生成签名密钥 $KS"
  keytool -genkeypair -v -keystore "$KS" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -alias hzwater \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=HzWater, O=Local, C=CN" >/dev/null 2>&1
fi

: "${KS_PASS:?keystore.jks 已存在, 请用 KS_PASS=<口令> ./build.sh 指定签名口令}"

java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias hzwater --min-sdk-version 23 \
  --out "$OUT/$APKNAME" "$OUT/aligned.apk"

java -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT/$APKNAME" 2>&1 | head -3
ls -la "$OUT/$APKNAME"
echo "DONE: $OUT/$APKNAME"
