#!/usr/bin/env bash
#
# Copyright (C) 2024-2026 OpenAni and contributors.
#
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
#
# https://github.com/open-ani/ani/blob/main/LICENSE
#
# 编 video-player 用的 FFmpeg 音频软解库 libffmpegJNI.so (media3 decoder_ffmpeg 的 JNI + 只含音频解码器的 FFmpeg 静态库),
# 输出到 src/androidMain/jniLibs/<abi>/. 对应的 Java 类在 src/androidMain/java/androidx/media3/decoder/ffmpeg/ (media3 同版本原样),
# 由 media3 的 DefaultRenderersFactory 按扩展渲染器模式加载, 见 FfmpegAudioFallback.
#
# 在 Linux / WSL 里跑 (FFmpeg 的 configure 要 Unix shell): `bash app/shared/video-player/native/media3-ffmpeg/build.sh`.
# 只需要 bash、curl、make、python3 与系统的 C 编译器 (configure 的探测用); NDK 与源码下载到 $WORK.
# 升 androidx-media3 时把 MEDIA3_VERSION 改成同一版本重跑, 并更新那几个 Java 文件.
#
# 对 media3 JNI 的唯一改动: TrueHD / MLP 打开解码器前按轨道的声道数设默认声道布局 —— FFmpeg 6.x 的这两个解码器没有声道布局时
# 每个包都报 AVERROR_INVALIDDATA, 一帧也解不出 (7.x 起不需要). media3 原本只给 μ-law / A-law 设; 跳转时 ffmpegReset 对 TrueHD
# 销毁重建上下文, 重建时带上原来的声道数.
set -euo pipefail

MEDIA3_VERSION=1.9.0
FFMPEG_VERSION=6.0 # media3 文档里测试过的版本
NDK_VERSION=r27c
ANDROID_API=21
ABIS=(armeabi-v7a arm64-v8a x86 x86_64)
DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd)

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../../src/androidMain/jniLibs"
WORK="${WORK:-$HOME/izuko-media3-ffmpeg}"
mkdir -p "$WORK"
cd "$WORK"

# ---- NDK (zip 里有符号链接与可执行位, 用 python 解开, 不依赖 unzip) ----
NDK="$WORK/android-ndk-$NDK_VERSION"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$TOOLCHAIN/clang" ]]; then
  curl -fsSL -o ndk.zip "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
  python3 - <<'PY'
import os, shutil, stat, zipfile
z = zipfile.ZipFile("ndk.zip")
for info in z.infolist():
    mode = info.external_attr >> 16
    if info.is_dir():
        os.makedirs(info.filename, exist_ok=True)
        continue
    os.makedirs(os.path.dirname(info.filename) or ".", exist_ok=True)
    if stat.S_ISLNK(mode):
        if os.path.lexists(info.filename):
            os.remove(info.filename)
        os.symlink(z.read(info).decode(), info.filename)
    else:
        with z.open(info) as src, open(info.filename, "wb") as dst:
            shutil.copyfileobj(src, dst)
        if mode & 0o777:
            os.chmod(info.filename, mode & 0o7777)
PY
  rm ndk.zip
fi

# ---- FFmpeg 源码与 media3 的 JNI ----
FFMPEG="$WORK/ffmpeg-$FFMPEG_VERSION"
[[ -d "$FFMPEG" ]] || curl -fsSL "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" | tar xJ
JNI="$WORK/media3-$MEDIA3_VERSION"
mkdir -p "$JNI"
curl -fsSL -o "$JNI/ffmpeg_jni.cc" \
  "https://raw.githubusercontent.com/androidx/media/$MEDIA3_VERSION/libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc"
python3 - "$JNI/ffmpeg_jni.cc" <<'PY'
import sys
path = sys.argv[1]
src = open(path, encoding="utf-8").read()
anchor = """    av_channel_layout_default(&context->ch_layout, rawChannelCount);
  }
"""
patch = anchor.rstrip("\n") + """ else if ((context->codec_id == AV_CODEC_ID_TRUEHD ||
              context->codec_id == AV_CODEC_ID_MLP) &&
             rawChannelCount > 0) {
    // FFmpeg 6.x: MLP / TrueHD decoders reject every packet until a channel layout is set.
    av_channel_layout_default(&context->ch_layout, rawChannelCount);
  }
"""
assert src.count(anchor) == 1, "anchor not found: media3 JNI changed, review the TrueHD patch"
src = src.replace(anchor, patch)
# ffmpegReset 对 TrueHD 是销毁重建上下文 (跳转、清缓冲时), 重建时也要带上声道数
reset_anchor = """    jboolean outputFloat =
        (jboolean)(context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
"""
reset_args = """                                /* rawSampleRate= */ -1,
                                /* rawChannelCount= */ -1);
"""
assert src.count(reset_anchor) == 1 and src.count(reset_args) == 1, "reset anchor not found: review the TrueHD patch"
src = src.replace(reset_anchor, reset_anchor + "    int channelCount = context->ch_layout.nb_channels;\n")
src = src.replace(reset_args, reset_args.replace("/* rawChannelCount= */ -1", "/* rawChannelCount= */ channelCount"))
open(path, "w", encoding="utf-8").write(src)
PY

# ---- 每个 ABI: 配置并编 FFmpeg 静态库, 再链出 libffmpegJNI.so ----
OPTIONS=(
  --target-os=android --enable-static --disable-shared --disable-doc --disable-programs --disable-everything
  --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter --disable-symver
  --enable-swresample --disable-v4l2-m2m --disable-vulkan
  --nm="$TOOLCHAIN/llvm-nm" --ar="$TOOLCHAIN/llvm-ar" --ranlib="$TOOLCHAIN/llvm-ranlib" --strip="$TOOLCHAIN/llvm-strip"
)
for d in "${DECODERS[@]}"; do OPTIONS+=(--enable-decoder="$d"); done

for abi in "${ABIS[@]}"; do
  case "$abi" in
    armeabi-v7a) triple=armv7a-linux-androideabi; arch=(--arch=arm --cpu=armv7-a --extra-cflags="-march=armv7-a -mfloat-abi=softfp" --extra-ldflags="-Wl,--fix-cortex-a8") ;;
    arm64-v8a) triple=aarch64-linux-android; arch=(--arch=aarch64 --cpu=armv8-a) ;;
    x86) triple=i686-linux-android; arch=(--arch=x86 --cpu=i686 --disable-asm) ;;
    x86_64) triple=x86_64-linux-android; arch=(--arch=x86_64 --cpu=x86-64 --disable-asm) ;;
  esac
  prefix="$TOOLCHAIN/$triple$ANDROID_API-"
  libs="$WORK/libs/$abi"
  (
    cd "$FFMPEG"
    make distclean >/dev/null 2>&1 || true
    ./configure --libdir="$libs" --incdir="$WORK/include" --cross-prefix="$prefix" "${arch[@]}" "${OPTIONS[@]}" >/dev/null
    make -j"$(nproc)" >/dev/null
    make install-libs install-headers >/dev/null
  )
  ldflags=(-Wl,-z,max-page-size=16384)
  [[ "$abi" == arm64-v8a ]] && ldflags+=(-Wl,-Bsymbolic)
  mkdir -p "$OUT/$abi"
  "${prefix}clang++" -std=c++11 -O2 -fPIC -shared -I"$WORK/include" "$JNI/ffmpeg_jni.cc" \
    "$libs/libswresample.a" "$libs/libavcodec.a" "$libs/libavutil.a" -landroid -llog -lm "${ldflags[@]}" \
    -o "$OUT/$abi/libffmpegJNI.so"
  "$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT/$abi/libffmpegJNI.so"
  echo "$abi: $(stat -c %s "$OUT/$abi/libffmpegJNI.so") bytes"
done
