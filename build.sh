#!/bin/bash
# 极简 Android 构建脚本 (无 gradle, 无 Android Studio).
# 关键约束:
#  1. aapt2 打不开含中文的绝对路径 -> 全程 cd 到 android 根目录用相对路径;
#  2. javac 21 生成的匿名内部类(带 NestHost)会让 build-tools 34 的 D8 8.2.2 抛 NPE
#     -> 源码禁用匿名内部类, 只用静态嵌套类或顶层类;
#  3. d8 的 --output 目录必须事先存在, 否则报 "Invalid output".
set -e

cd "F:/DSH工作区/_tools/android"

BT="bt34/android-14"
PLAT="plat/android-34-ext12/android.jar"
APP="app"
OUT="app/build"
APKNAME="${1:-喝水.apk}"
KS="keystore.jks"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT" \
  --min-sdk-version 23 \
  --target-sdk-version 33 \
  --version-code 30 --version-name 1.2.0 \
  -A "$APP/assets" \
  --auto-add-overlay

echo "[3/6] javac"
find "$APP/src" -name '*.java' > "$OUT/srcs.txt"
find "$OUT" -name 'R.java' >> "$OUT/srcs.txt"
# zxing-core 是 Java 8 (major 52) 预编译 jar, 直接进 classpath
# javac 的退出码必须尊重: 之前用管道接 grep 把错误吞掉了, 源码编译失败也照样出包,
# 装到机器上才发现某个类根本不存在.
JLOG="$OUT/javac.log"
if ! javac -encoding UTF-8 --release 8 -classpath "$PLAT;libs/zxing-core.jar;libs/alipay_x/classes.jar" \
    -d "$OUT/classes" @"$OUT/srcs.txt" > "$JLOG" 2>&1; then
  grep -E "错误|error" "$JLOG" | head -40
  echo "javac FAILED, 详见 $JLOG"
  exit 1
fi
grep -vE "^(注意|注|Note|警告|warning)" "$JLOG" | head -20 || true
# 把 zxing 的 class 解进同一个目录, d8 一并 dex 化
if [ -f libs/zxing-core.jar ]; then
  (cd "$OUT/classes" && "/c/Java  321/bin/jar" xf "$OLDPWD/libs/zxing-core.jar" 2>/dev/null) || \
  (cd "$OUT/classes" && unzip -oq "$OLDPWD/libs/zxing-core.jar" 2>/dev/null) || true
  echo "  zxing classes merged"
fi
# 支付宝 SDK class 合并(aliPay 桥依赖 com.alipay.sdk.app.PayTask)
if [ -d libs/alipay_x/com ]; then
  cp -r libs/alipay_x/com "$OUT/classes/" 2>/dev/null || true
  echo "  alipay classes merged"
fi

echo "[4/6] d8"
# 类多时命令行超长(支付宝 SDK 700 类), python 打 jar 再喂 d8(单参数)
KJAR="$OUT/classes.jar"
PYEXE="${PYEXE:-python}"
"$PYEXE" "F:/DSH工作区/_tools/adtmp/mkjar.py" || { cd "$OUT/classes" && "$PYEXE" "F:/DSH工作区/_tools/adtmp/mkjar.py"; }
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLAT" --min-api 23 --release \
  --output "$OUT/dex" "$KJAR"

echo "[5/6] 合入 dex + assets 已在 link 阶段打入"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# 本机无 zip 命令, 用 python 的 zipfile 追加 classes.dex (APK 允许重复条目, aapt2 不会写 classes.dex)
python - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('  appended classes.dex ->', apk)
PY

echo "[6/6] zipalign + apksigner"
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
  echo "  生成签名密钥 $KS"
  "/c/Java  321/bin/keytool" -genkeypair -v -keystore "$KS" \
    -storepass hzwater -keypass hzwater -alias hzwater \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=HzWater, O=Local, C=CN" >/dev/null 2>&1
fi

java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:hzwater --key-pass pass:hzwater \
  --ks-key-alias hzwater --min-sdk-version 23 \
  --out "$OUT/$APKNAME" "$OUT/aligned.apk"

java -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT/$APKNAME" 2>&1 | head -3
ls -la "$OUT/$APKNAME"
echo "DONE: $OUT/$APKNAME"
