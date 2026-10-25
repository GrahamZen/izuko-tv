#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

fail() { printf '%s\n' "$*" >&2; exit 1; }

check_host() {
    [[ "${CIRCLECI:-}" == true ]] || fail "This script runs only in a CircleCI job."
    [[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || fail "Apple Silicon macOS is required."
    local memory_bytes free_kib
    memory_bytes=$(sysctl -n hw.memsize)
    free_kib=$(df -k . | awk 'END {print $4}')
    (( memory_bytes >= 24 * 1024 * 1024 * 1024 )) || fail "At least 24 GiB RAM is required."
    (( free_kib >= 100 * 1024 * 1024 )) || fail "At least 100 GiB free disk space is required."
    xcodebuild -version
    df -h .
}

prepare() {
    check_host
    : "${BASH_ENV:?CircleCI must provide BASH_ENV}"
    git submodule update --init --recursive

    # Use the same JBR with JCEF as the repository's macOS workflow.
    local tools_dir jbr_archive jbr_url expected actual
    tools_dir="$HOME/.local/izuko-ci"
    jbr_archive=jbrsdk_jcef-21.0.11-osx-aarch64-b1163.116.tar.gz
    jbr_url="https://cache-redirector.jetbrains.com/intellij-jbr/$jbr_archive"
    mkdir -p "$tools_dir/jbr"
    curl --fail --location --retry 3 "$jbr_url.checksum" -o "$tools_dir/jbr.checksum"
    curl --fail --location --retry 3 "$jbr_url" -o "$tools_dir/$jbr_archive"
    expected=$(awk 'NR == 1 {print tolower($1)}' "$tools_dir/jbr.checksum")
    actual=$(shasum -a 512 "$tools_dir/$jbr_archive" | awk '{print $1}')
    [[ "$expected" =~ ^[0-9a-f]{128}$ && "$actual" == "$expected" ]] || fail "JBR checksum verification failed."
    tar -xzf "$tools_dir/$jbr_archive" -C "$tools_dir/jbr" --strip-components=1
    export JAVA_HOME="$tools_dir/jbr/Contents/Home"
    [[ -x "$JAVA_HOME/bin/java" ]] || fail "JBR archive does not contain a macOS JDK."
    export PATH="$JAVA_HOME/bin:$PATH"

    local sdk_manager sdk_root
    sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
    sdk_manager=$(command -v sdkmanager || true)
    if [[ -z "$sdk_manager" && -x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" ]]; then
        sdk_manager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
    fi
    if [[ -z "$sdk_manager" ]]; then
        brew install --cask android-commandlinetools
        sdk_manager="$(brew --prefix)/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager"
    fi
    [[ -x "$sdk_manager" ]] || fail "Android SDK command-line tools are unavailable."
    mkdir -p "$sdk_root"
    # Capture sdkmanager's status separately from yes, which exits on a closed pipe.
    local license_status
    set +o pipefail
    yes | "$sdk_manager" --sdk_root="$sdk_root" --licenses > "$tools_dir/android-licenses.log" 2>&1
    license_status=$?
    set -o pipefail
    (( license_status == 0 )) || fail "Android SDK license setup failed; see $tools_dir/android-licenses.log."
    "$sdk_manager" --sdk_root="$sdk_root" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
    export ANDROID_HOME="$sdk_root"
    export ANDROID_SDK_ROOT="$sdk_root"

    command -v pod >/dev/null || brew install cocoapods
    pod --version
    java -version

    # These settings also apply to the Gradle invocation made by Xcode's Pod script.
    # A single worker avoids simultaneous Kotlin/Native links on the 28 GB VM.
    mkdir -p "$HOME/.gradle"
    cat >> "$HOME/.gradle/gradle.properties" <<'PROPERTIES'
org.gradle.jvmargs=-Xmx12g -Dfile.encoding=UTF-8 -Dkotlin.daemon.jvm.options=-Xmx16g
kotlin.daemon.jvmargs=-Xmx16g
org.gradle.parallel=false
org.gradle.workers.max=1
PROPERTIES

    [[ ! -e local.properties ]] || fail "The CI checkout must not contain local.properties."
    cat > local.properties <<PROPERTIES
ani.enable.ios=true
ani.build.framework=true
ani.android.abis=arm64-v8a
ani.enable.firebase=false
kotlin.native.ignoreDisabledTargets=true
sdk.dir=$sdk_root
PROPERTIES

    {
        printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
        printf 'export ANDROID_HOME=%q\n' "$ANDROID_HOME"
        printf 'export ANDROID_SDK_ROOT=%q\n' "$ANDROID_SDK_ROOT"
        printf 'export PATH=%q:"$PATH"\n' "$JAVA_HOME/bin"
    } >> "$BASH_ENV"
    chmod +x gradlew scripts/gradle-lock.sh
}

build() {
    check_host
    local build_task
    case "${IOS_BUILD_TYPE:-}" in
        debug) build_task=:app:ios:buildDebugIpa ;;
        release) build_task=:app:ios:buildReleaseIpa ;;
        *) fail "IOS_BUILD_TYPE must be debug or release." ;;
    esac
    ./scripts/gradle-lock.sh :app:ios:podInstall :app:ios:patchInfoPlist --max-workers=1 --no-parallel --stacktrace
    ./scripts/gradle-lock.sh "$build_task" --max-workers=1 --no-parallel --stacktrace
}

case "${1:-}" in
    prepare) prepare ;;
    build) build ;;
    *) fail "Usage: bash .circleci/ios-build.sh prepare|build" ;;
esac
