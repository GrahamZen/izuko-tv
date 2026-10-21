#!/usr/bin/env bash
# 重编 anitorrent 原生库 (上游 v0.2.0 + anitorrent.patch, libtorrent v2.0.10 + libtorrent.patch),
# 产物写进同目录的 repo/ (本地 Maven 仓库).
# 说明见 README.md. Windows 上用 Git Bash 跑.
#
#   NDK=/path/to/android-ndk-r27c NINJA=/path/to/ninja SWIG=/path/to/swig torrent/anitorrent/native/build.sh
#
# 可选: WORK (中间文件, 默认 build/anitorrent-native), JDK_BIN (含 javac), ABIS.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
: "${NDK:?set NDK to an Android NDK r27c directory}"
: "${NINJA:?set NINJA to a ninja executable}"
: "${SWIG:?set SWIG to a SWIG 4.2.1 executable}"
WORK="${WORK:-$HERE/../../../build/anitorrent-native}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64 x86}"
JDK_BIN="${JDK_BIN:-$(dirname "$(command -v javac)")}"
VERSION=0.2.0-izuko6
UPSTREAM_COMMIT=05756d034b6e72b2dc327c00182dccdd3bdf22ce # open-ani/anitorrent v0.2.0
BASE_AAR_URL=https://repo1.maven.org/maven2/org/openani/anitorrent/anitorrent-native-android/0.2.0/anitorrent-native-android-0.2.0.aar

mkdir -p "$WORK"
SRC="$WORK/anitorrent"
if [ ! -d "$SRC/.git" ]; then
  # 不随本机的 core.autocrlf 改换行, 补丁按仓库里的原样 (LF) 打
  git clone -q -c core.autocrlf=false https://github.com/open-ani/anitorrent.git "$SRC"
fi
git -C "$SRC" checkout -q --force "$UPSTREAM_COMMIT"
git -C "$SRC" clean -qfd
git -C "$SRC" apply "$HERE/anitorrent.patch"
git -C "$SRC" add -A
NATIVE="$SRC/anitorrent-native"

# libtorrent 不让 CMake 自己拉: 要先打上补丁
LT="$WORK/libtorrent"
if [ ! -d "$LT/.git" ]; then
  git clone -q -c core.autocrlf=false --branch v2.0.10 --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/arvidn/libtorrent.git "$LT"
fi
git -C "$LT" checkout -q --force v2.0.10
git -C "$LT" clean -qfd
git -C "$LT" apply "$HERE/libtorrent.patch"

# 绑定由补丁里的 anitorrent.i 生成 (补丁里也带着生成好的, 这里重新生成一遍核对)
"$SWIG" -java -c++ -directors -cppext cpp -addextern \
  -o "$NATIVE/gen/cpp/anitorrent_wrap.cpp" \
  -outdir "$NATIVE/gen/java/org/openani/anitorrent/binding" \
  -package org.openani.anitorrent.binding "$NATIVE/anitorrent.i"
git -C "$SRC" -c core.autocrlf=false diff --quiet --ignore-cr-at-eol -- anitorrent-native/gen ||
  { echo "generated bindings differ from the patch, regenerate the patch" >&2; exit 1; }

STRIP="$NDK/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt")/bin/llvm-strip"
LIBS="$WORK/jni"
rm -rf "$LIBS"
for abi in $ABIS; do
  out="$WORK/cmake-$abi"
  # 与上游安卓包同样的选项; libtorrent 由 CMakeLists 里的 FetchContent 拉 v2.0.10.
  # API 级别至少 24: 低于它 libtorrent 用 netlink 枚举网卡, Android 11 起应用不许绑 netlink, 会一个连接都建不起来
  cmake -S "$NATIVE" -B "$out" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_NDK_HOME="$NDK" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DFETCHCONTENT_SOURCE_DIR_LIBTORRENT="$LT" >/dev/null
  cmake --build "$out" --target anitorrent -j 12 >/dev/null
  mkdir -p "$LIBS/$abi"
  "$STRIP" --strip-unneeded -o "$LIBS/$abi/libanitorrent.so" "$out/libanitorrent.so"
  "$STRIP" --strip-unneeded -o "$LIBS/$abi/libtorrent-rasterbar.so" "$out/_deps/libtorrent-build/libtorrent-rasterbar.so"
  echo "built $abi"
done

# Java 绑定 (与上游一样是 Java 8 字节码)
classes="$WORK/classes"
rm -rf "$classes" && mkdir -p "$classes"
"$JDK_BIN/javac" --release 8 -nowarn -d "$classes" "$NATIVE"/gen/java/org/openani/anitorrent/binding/*.java

base_aar="$WORK/anitorrent-native-android-0.2.0.aar"
[ -s "$base_aar" ] || curl -sSfL -o "$base_aar" "$BASE_AAR_URL"

REPO="$HERE/repo/org/openani/anitorrent"
uv run python - "$base_aar" "$LIBS" "$classes" "$REPO" "$VERSION" "$HERE/pom.xml.in" <<'PYEOF'
import pathlib, sys, zipfile

base_aar, libs, classes, repo, version, pom_template = sys.argv[1:]
libs, classes, repo = pathlib.Path(libs), pathlib.Path(classes), pathlib.Path(repo)
template = pathlib.Path(pom_template).read_text(encoding="utf-8")
STAMP = (1980, 1, 1, 0, 0, 0)  # 固定时间戳, 同样的输入得到同样的文件


def put(z, name, data):
    info = zipfile.ZipInfo(name, STAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info, data)


def publish(artifact, ext, write, packaging, dependencies):
    d = repo / artifact / version
    d.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(d / f"{artifact}-{version}.{ext}", "w") as z:
        write(z)
    pom = (template.replace("@ARTIFACT@", artifact).replace("@VERSION@", version)
           .replace("@PACKAGING@", packaging).replace("@DEPENDENCIES@", dependencies))
    (d / f"{artifact}-{version}.pom").write_text(pom, encoding="utf-8")


def write_jar(z):
    put(z, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
    for f in sorted(classes.rglob("*.class")):
        put(z, f.relative_to(classes).as_posix(), f.read_bytes())


def write_aar(z):
    # 清单、R.txt 等沿用上游的包, 只换 jni/ 下的库
    with zipfile.ZipFile(base_aar) as base:
        for item in base.infolist():
            if not item.filename.startswith("jni/"):
                put(z, item.filename, base.read(item))
    for f in sorted(libs.rglob("*.so")):
        put(z, "jni/" + f.relative_to(libs).as_posix(), f.read_bytes())


jni_dep = f"""
    <dependency>
      <groupId>org.openani.anitorrent</groupId>
      <artifactId>anitorrent-native-desktop-jni</artifactId>
      <version>{version}</version>
      <scope>compile</scope>
    </dependency>"""
publish("anitorrent-native-desktop-jni", "jar", write_jar, "jar", "")
publish("anitorrent-native-android", "aar", write_aar, "aar", jni_dep)
print("published", version)
PYEOF
