#!/usr/bin/env bash
#
# 编安装助手 (app/installer) 并发布到固定的 `installer` release, 下载地址恒为
#   https://github.com/<仓库>/releases/download/installer/install-izuko.apk
# (README 与发版模板的下载段都指向它).
#
# 助手只发布一次: 最新版本号与下载镜像都是它运行时现查的. 只有发布包的命名规则 / 仓库地址变了,
# 或者新的 Android 抬高了允许安装的最低 targetSdk, 才要重发 (versionCode 保持 1, 正式版装上来才是升级).
#
# 用法:
#   scripts/publish-installer.sh            只编并核对, 产物在 build/installer/install-izuko.apk
#   scripts/publish-installer.sh publish    再上传 (release 不存在就建, 不标成 Latest; 已有同名文件就覆盖)
#
# 需要: local.properties 里的发布签名 (signing_release_*, 与正式版同一把), Android SDK 的 build-tools, 已登录的 gh.
set -euo pipefail
cd "$(dirname "$0")/.."

TAG=installer
NAME=install-izuko.apk
# 正式版的签名证书 (SHA-256 摘要开头): 签名不同的话, 正式版装不上来覆盖它
EXPECTED_CERT=${EXPECTED_CERT:-937ac7ade352f6d423d472d21bd5424d26633d7cad04d6f6c639d6c97213200d}
REPO=$(grep '^ani.repository=' gradle.properties | cut -d= -f2-)
APP_ID=io.github.grahamzen.anime.tv

die() { echo "publish-installer: $*" >&2; exit 1; }

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ]; then
  sdk=$(grep '^sdk.dir=' local.properties 2>/dev/null | cut -d= -f2- | sed 's#\\:#:#g; s#\\\\#/#g')
fi
[ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || die "Android SDK not found (set ANDROID_HOME or sdk.dir)"
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
tool() { if [ -e "$bt/$1.exe" ]; then echo "$bt/$1.exe"; elif [ -e "$bt/$1.bat" ]; then echo "$bt/$1.bat"; else echo "$bt/$1"; fi; }

./scripts/gradle-lock.sh :app:installer:assembleRelease
mkdir -p build/installer
out=build/installer/$NAME
cp app/installer/build/outputs/apk/release/installer-release.apk "$out"

badging=$("$(tool aapt2)" dump badging "$out")
echo "$badging" | grep -q "^package: name='$APP_ID' versionCode='1' " || die "unexpected package line: $(echo "$badging" | head -1)"
echo "$badging" | grep -q "^minSdkVersion:'19'" || die "minSdk is not 19"
certs=$("$(tool apksigner)" verify --verbose --print-certs "$out")
echo "$certs" | grep -q "Verified using v1 scheme (JAR signing): true" || die "missing v1 signature (Android 4.4~6 need it)"
echo "$certs" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" || die "missing v2 signature"
echo "$certs" | grep -qi "certificate SHA-256 digest: $EXPECTED_CERT" || die "signed with a different certificate"
echo "OK: $out ($(wc -c < "$out") bytes), $APP_ID versionCode 1, signed with $EXPECTED_CERT"

[ "${1:-}" = publish ] || exit 0

if ! gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  # 不标成 Latest: 老版本的更新检查经 ghfast 读 releases/latest 的跳转;
  # tag 不是版本号, 应用内更新 (只认主版本号 < 6 的 tag) 与 jsDelivr 的版本范围都会跳过它
  gh release create "$TAG" --repo "$REPO" --target main --latest=false \
    --title "Izuko TV 安装助手" \
    --notes "第一次在电视上装 Izuko TV、不知道下哪个安装包，或者装的时候提示「解析错误」时，先装这里的 \`$NAME\`（Android 4.4 及以上都能装）。它按电视的系统版本和架构挑选安装包，从 GitHub 或加速线路下载、校验后装上 Izuko TV（装上后替换掉助手本身）；装不上时说明原因。

已经装了 Izuko TV 的电视请用应用内更新，装助手会被系统当成降级拒绝。

If Izuko TV fails to install on your TV (for example with a parse error), install \`$NAME\` first: it picks the right package for the device, downloads it and installs Izuko TV over itself."
fi
gh release upload "$TAG" "$out" --repo "$REPO" --clobber
echo "Published: https://github.com/$REPO/releases/download/$TAG/$NAME"
