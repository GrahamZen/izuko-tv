#!/usr/bin/env bash
# 准备本地 pod onnxruntime-c 1.22.0 (静态 xcframework、头文件、许可证): 下载 pod 包, 按 SHA-256 核对, 解到 <输出目录>,
# 再放上官方 podspec (见同目录 README.md)。输出目录里已经是这一份就直接返回。
#
# 用法: fetch-onnxruntime-c.sh <输出目录> [下载缓存目录]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:?用法: fetch-onnxruntime-c.sh <输出目录> [下载缓存目录]}"
CACHE="${2:-$OUT-download}"
VERSION="1.22.0"
ZIP_SHA256="90d9de5a139087a6b05a18125d01d01d198820e1731e6f0f11b38749b2ab181f"
# 依次试: 官方下载站, 再是固定到某个提交的副本; 哪个下下来的校验值对上用哪个
URLS=(
  "https://download.onnxruntime.ai/pod-archive-onnxruntime-c-$VERSION.zip"
  "https://raw.githubusercontent.com/zubair-io/Maple/e4e240593bf44053265f017ac6e25b03ff293be2/src/apple/vendor/ort-ios/pod-archive-onnxruntime-c-$VERSION.zip"
)

sha256() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'; else sha256sum "$1" | awk '{print $1}'; fi
}

if [ -f "$OUT/.sha256" ] && [ "$(cat "$OUT/.sha256")" = "$ZIP_SHA256" ] && [ -d "$OUT/onnxruntime.xcframework" ]; then
  echo "onnxruntime-c $VERSION 已就绪: $OUT"
  exit 0
fi

mkdir -p "$CACHE"
ZIP="$CACHE/pod-archive-onnxruntime-c-$VERSION.zip"
if [ ! -f "$ZIP" ] || [ "$(sha256 "$ZIP")" != "$ZIP_SHA256" ]; then
  rm -f "$ZIP"
  for url in "${URLS[@]}"; do
    echo "下载 $url"
    if curl -fsSL --retry 2 --connect-timeout 20 --max-time 900 -o "$ZIP.part" "$url" && [ "$(sha256 "$ZIP.part")" = "$ZIP_SHA256" ]; then
      mv "$ZIP.part" "$ZIP"
      break
    fi
    echo "  取不到或校验值不符, 试下一个"
    rm -f "$ZIP.part"
  done
fi
if [ ! -f "$ZIP" ]; then
  echo "onnxruntime-c $VERSION 的 pod 包哪里都取不到 (要的 SHA-256: $ZIP_SHA256)" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"
unzip -q "$ZIP" -d "$OUT"
cp "$HERE/onnxruntime-c.podspec.json" "$OUT/"
echo "$ZIP_SHA256" > "$OUT/.sha256"
echo "onnxruntime-c $VERSION 已解到 $OUT"
