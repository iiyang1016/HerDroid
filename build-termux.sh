#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_REAL_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$SOURCE_DIR"

WORK_ROOT="$HOME/.cache/herdroid-termux-build"
WORKSPACE_DIR="$WORK_ROOT/worktree"
LOG_DIR="$WORK_ROOT/logs"

OUTPUT_DIR="${HERDROID_OUTPUT_DIR:-$HOME/storage/downloads/arc/HerDroid}"
OUTPUT_APK="$OUTPUT_DIR/HerDroid-debug.apk"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android-sdk-termux}}"

PLATFORM_VERSION="37.0"
PLATFORM_ZIP="platform-37.0_r01.zip"
PLATFORM_SHA256="19bdcf42de0cb0e9500a27da6833fc30cdd49a7ec690aa5cabaa0ef893af9ebe"
PLATFORM_URL="https://dl.google.com/android/repository/${PLATFORM_ZIP}"

BUILD_TOOLS_VERSION="36.0.0"
BUILD_TOOLS_ZIP="build-tools_r36_linux.zip"
BUILD_TOOLS_URL="https://dl.google.com/android/repository/${BUILD_TOOLS_ZIP}"
BUILD_TOOLS_CACHE="$WORK_ROOT/$BUILD_TOOLS_ZIP"

RUN_ID="$(date '+%Y%m%d-%H%M%S')"
LIVE_LOG="$LOG_DIR/herdroid-build-$RUN_ID.log"
FAIL_LOG="$OUTPUT_DIR/herdroid-build-failed.txt"
STAMPED_FAIL_LOG="$OUTPUT_DIR/herdroid-build-failed-$RUN_ID.txt"

APK_PATH=""
STAGED_BUILD=0

log() { printf '\n\033[1;34m[HerDroid]\033[0m %s\n' "$*"; }
warn() { printf '\n\033[1;33m[HerDroid]\033[0m %s\n' "$*" >&2; }
die() { printf '\n\033[1;31m[HerDroid]\033[0m %s\n' "$*" >&2; exit 1; }

if [[ "${PREFIX:-}" != *com.termux* ]]; then
    die "Run this script inside the official Termux app environment."
fi

mkdir -p "$LOG_DIR"

prepare_output_dir() {
    if [[ "$OUTPUT_DIR" == "$HOME/storage/downloads"* ]]; then
        [[ -d "$HOME/storage/downloads" ]] \
            || die "Termux shared storage is not available. Run: termux-setup-storage"
    fi

    mkdir -p "$OUTPUT_DIR" \
        || die "Could not create output directory: $OUTPUT_DIR"

    local probe="$OUTPUT_DIR/.herdroid-write-test-$$"
    if ! : > "$probe" 2>/dev/null; then
        die "Output directory is not writable: $OUTPUT_DIR"
    fi
    rm -f "$probe"

    log "Build outputs: $OUTPUT_DIR"
}

install_packages() {
    local packages=(openjdk-17 gradle aapt aidl curl unzip ca-certificates tar)
    local missing=()
    local package

    for package in "${packages[@]}"; do
        dpkg -s "$package" >/dev/null 2>&1 || missing+=("$package")
    done

    if (( ${#missing[@]} == 0 )); then
        log "Termux build dependencies already installed"
        return
    fi

    log "Installing missing Termux packages: ${missing[*]}"
    pkg update -y
    apt-get install -y --no-install-recommends "${missing[@]}"
}

prepare_workspace() {
    if [[ "$SOURCE_REAL_DIR" == /storage/* || "$SOURCE_REAL_DIR" == /sdcard/* ]]; then
        STAGED_BUILD=1
        log "Shared-storage checkout detected; staging build in Termux private storage"
        rm -rf "$WORKSPACE_DIR"
        mkdir -p "$WORKSPACE_DIR"

        (
            cd "$SOURCE_DIR"
            tar \
                --exclude='./.git' \
                --exclude='./.gradle' \
                --exclude='./build' \
                --exclude='./app/build' \
                --exclude='./herdroid-build-failed.txt' \
                --exclude='./herdroid-build-failed-*.txt' \
                --exclude='./HerDroid-debug.apk' \
                -cf - .
        ) | (
            cd "$WORKSPACE_DIR"
            tar -xf -
        )

        PROJECT_DIR="$WORKSPACE_DIR"
    else
        PROJECT_DIR="$SOURCE_DIR"
    fi

    APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
}

configure_java() {
    local java17="$PREFIX/lib/jvm/java-17-openjdk"
    [[ -x "$java17/bin/java" ]] || die "OpenJDK 17 was not installed correctly."

    export JAVA_HOME="$java17"
    export PATH="$JAVA_HOME/bin:$PREFIX/bin:$PATH"

    log "Using $(java -version 2>&1 | head -n 1)"

    local gradle_version
    gradle_version="$(gradle --version | awk '/^Gradle / {print $2; exit}')"
    [[ -n "$gradle_version" ]] || die "Could not determine the installed Gradle version."

    if [[ "$(printf '%s\n' 9.5.0 "$gradle_version" | sort -V | head -n 1)" != "9.5.0" ]]; then
        die "HerDroid requires Gradle 9.5+. Installed: $gradle_version. Run pkg upgrade and retry."
    fi

    command -v aapt >/dev/null 2>&1 || die "aapt is unavailable. Try: pkg reinstall aapt"
    command -v aapt2 >/dev/null 2>&1 || die "aapt2 is unavailable. Try: pkg reinstall aapt"
    command -v aidl >/dev/null 2>&1 || die "aidl is unavailable. Try: pkg install aidl"
    command -v zipalign >/dev/null 2>&1 || die "zipalign is unavailable. Try: pkg reinstall aapt"

    log "Using Gradle $gradle_version and $(aapt2 version 2>&1 | head -n 1)"
}

accept_sdk_license() {
    local license_dir="$SDK_ROOT/licenses"
    local license_file="$license_dir/android-sdk-license"
    local current_license_hash="24333f8a63b6825ea9c5514f83c2829b004d1fee"

    if [[ -f "$license_file" ]] && grep -qx "$current_license_hash" "$license_file"; then
        log "Android SDK license already accepted"
        return
    fi

    printf '\nAndroid SDK Platform %s requires Google\x27s Android SDK License Agreement.\n' "$PLATFORM_VERSION"
    printf 'Type YES to confirm that you accept the Android SDK License Agreement: '

    local answer
    read -r answer
    [[ "$answer" == "YES" ]] || die "Android SDK license was not accepted; build cancelled."

    mkdir -p "$license_dir"
    touch "$license_file"

    {
        cat "$license_file"
        printf '%s\n' \
            '24333f8a63b6825ea9c5514f83c2829b004d1fee' \
            '8933bad161af4178b1185d1a37fbf41ea5269c55' \
            'd56f5187479451eabf01fb78af6dfcb131a6481e'
    } | awk 'NF && !seen[$0]++' > "$license_file.tmp"

    mv "$license_file.tmp" "$license_file"
    log "Android SDK license acceptance recorded"
}

install_platform() {
    local platform_dir="$SDK_ROOT/platforms/android-$PLATFORM_VERSION"
    mkdir -p "$SDK_ROOT/platforms"

    local duplicate
    while IFS= read -r duplicate; do
        [[ "$duplicate" == "$platform_dir" ]] && continue
        warn "Removing duplicate Android platform directory: $duplicate"
        rm -rf "$duplicate"
    done < <(find "$SDK_ROOT/platforms" -maxdepth 1 -mindepth 1 -type d \
        -name "android-$PLATFORM_VERSION-*" -print 2>/dev/null || true)

    if [[ -f "$platform_dir/android.jar" ]]; then
        log "Android SDK Platform $PLATFORM_VERSION already installed"
        return
    fi

    log "Installing Android SDK Platform $PLATFORM_VERSION"

    local tmp
    tmp="$(mktemp -d)"

    curl -fL --retry 3 --retry-delay 2 "$PLATFORM_URL" -o "$tmp/$PLATFORM_ZIP"
    printf '%s  %s\n' "$PLATFORM_SHA256" "$tmp/$PLATFORM_ZIP" | sha256sum -c -
    unzip -q "$tmp/$PLATFORM_ZIP" -d "$tmp/unpacked"

    local found
    found="$(find "$tmp/unpacked" -type f -name android.jar -print -quit || true)"
    [[ -n "$found" ]] || die "Android $PLATFORM_VERSION platform archive extracted, but android.jar was not found."

    rm -rf "$platform_dir"
    mkdir -p "$platform_dir"
    cp -a "$(dirname "$found")/." "$platform_dir/"

    rm -rf "$tmp"
}

install_official_build_tools_layout() {
    local bt="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
    local marker="$bt/.herdroid-hybrid-build-tools"
    local needs_install=0

    local required
    for required in \
        source.properties \
        core-lambda-stubs.jar \
        lib/d8.jar \
        lib/apksigner.jar \
        d8 \
        apksigner \
        dexdump \
        split-select \
        lld \
        llvm-rs-cc; do
        [[ -e "$bt/$required" ]] || needs_install=1
    done

    if [[ -f "$marker" && $needs_install -eq 0 ]]; then
        return
    fi

    log "Bootstrapping official Android Build Tools $BUILD_TOOLS_VERSION layout"
    mkdir -p "$WORK_ROOT"

    if [[ ! -s "$BUILD_TOOLS_CACHE" ]] || ! unzip -tq "$BUILD_TOOLS_CACHE" >/dev/null 2>&1; then
        rm -f "$BUILD_TOOLS_CACHE"
        log "Downloading official Google Build Tools $BUILD_TOOLS_VERSION (one-time)"
        curl -fL --retry 3 --retry-delay 2 "$BUILD_TOOLS_URL" -o "$BUILD_TOOLS_CACHE"
        unzip -tq "$BUILD_TOOLS_CACHE" >/dev/null \
            || die "Downloaded Build Tools archive failed ZIP integrity validation."
    fi

    local tmp
    tmp="$(mktemp -d)"
    unzip -q "$BUILD_TOOLS_CACHE" -d "$tmp"

    local core_stub
    core_stub="$(find "$tmp" -type f -name core-lambda-stubs.jar -print -quit || true)"
    [[ -n "$core_stub" ]] || die "Official Build Tools archive is missing core-lambda-stubs.jar."

    local official_dir
    official_dir="$(dirname "$core_stub")"

    [[ -f "$official_dir/lib/d8.jar" ]] \
        || die "Official Build Tools archive is missing lib/d8.jar."
    [[ -f "$official_dir/lib/apksigner.jar" ]] \
        || die "Official Build Tools archive is missing lib/apksigner.jar."

    rm -rf "$bt"
    mkdir -p "$bt"
    cp -a "$official_dir/." "$bt/"

    printf 'HerDroid hybrid Build Tools: official Google layout + Termux ARM64 native frontends\n' > "$marker"
    rm -rf "$tmp"
}

configure_build_tools() {
    local bt="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"

    install_official_build_tools_layout

    log "Overlaying Termux ARM64-native Android build tools"

    ln -sfn "$(command -v aapt)" "$bt/aapt"
    ln -sfn "$(command -v aapt2)" "$bt/aapt2"
    ln -sfn "$(command -v aidl)" "$bt/aidl"
    ln -sfn "$(command -v zipalign)" "$bt/zipalign"

    chmod +x "$bt/d8" "$bt/apksigner" 2>/dev/null || true

    local required
    for required in \
        aapt \
        aapt2 \
        aidl \
        zipalign \
        d8 \
        apksigner \
        dexdump \
        split-select \
        lld \
        llvm-rs-cc \
        core-lambda-stubs.jar \
        lib/d8.jar \
        lib/apksigner.jar; do
        [[ -e "$bt/$required" ]] || die "Build Tools $BUILD_TOOLS_VERSION is incomplete: missing $required"
    done

    log "Hybrid Android Build Tools $BUILD_TOOLS_VERSION ready"
}

configure_project() {
    export ANDROID_HOME="$SDK_ROOT"
    export ANDROID_SDK_ROOT="$SDK_ROOT"
    printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$PROJECT_DIR/local.properties"
}

choose_heap() {
    local kb
    kb="$(awk '/MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"

    if (( kb >= 6000000 )); then
        echo 2048m
    elif (( kb >= 4000000 )); then
        echo 1536m
    else
        echo 1024m
    fi
}

gradle_common_args() {
    local heap="$1"

    printf '%s\n' \
        '--no-daemon' \
        '--max-workers=1' \
        '--no-parallel' \
        '--no-configuration-cache' \
        '--stacktrace' \
        '--info' \
        '--warning-mode' \
        'all' \
        "-Dorg.gradle.jvmargs=-Xmx$heap -Dfile.encoding=UTF-8" \
        '-Pkotlin.compiler.execution.strategy=in-process' \
        "-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2"
}

build_app() {
    local heap
    heap="$(choose_heap)"

    log "Building HerDroid debug APK (heap: $heap, one worker)"
    cd "$PROJECT_DIR"

    local args=()
    while IFS= read -r arg; do
        args+=("$arg")
    done < <(gradle_common_args "$heap")

    if [[ "${1:-}" == "--clean" ]]; then
        gradle :app:clean "${args[@]}"
    fi

    gradle :app:assembleDebug "${args[@]}"

    [[ -f "$APK_PATH" ]] \
        || die "Gradle finished but the expected APK was not found: $APK_PATH"

    cp -f "$APK_PATH" "$OUTPUT_APK"
    [[ -f "$OUTPUT_APK" ]] || die "APK build succeeded but copy to Downloads failed: $OUTPUT_APK"

    log "Build complete"
    printf '\nAPK: %s\n' "$OUTPUT_APK"
}

write_failure_report() {
    local status="$1"
    local expected_workspace="$SOURCE_DIR"

    if [[ "$SOURCE_REAL_DIR" == /storage/* || "$SOURCE_REAL_DIR" == /sdcard/* ]]; then
        expected_workspace="$WORKSPACE_DIR"
    fi

    {
        printf 'HerDroid Termux build failure report\n'
        printf '===================================\n'
        printf 'Exit code: %s\n' "$status"
        printf 'Timestamp: %s\n' "$(date -Iseconds 2>/dev/null || date)"
        printf 'Source: %s\n' "$SOURCE_DIR"
        printf 'Source real path: %s\n' "$SOURCE_REAL_DIR"
        printf 'Build workspace: %s\n' "$expected_workspace"
        printf 'Output directory: %s\n' "$OUTPUT_DIR"
        printf 'SDK root: %s\n' "$SDK_ROOT"
        printf 'PREFIX: %s\n' "${PREFIX:-unset}"

        printf '\n--- Device / environment ---\n'
        uname -a 2>&1 || true
        if command -v termux-info >/dev/null 2>&1; then
            termux-info 2>&1 || true
        fi

        printf '\n--- Java 17 ---\n'
        "$PREFIX/lib/jvm/java-17-openjdk/bin/java" -version 2>&1 || true

        printf '\n--- Gradle ---\n'
        JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk" gradle --version 2>&1 || true

        printf '\n--- Android tools ---\n'
        aapt2 version 2>&1 || true
        printf 'aidl: '
        aidl --help 2>&1 | head -n 2 || true

        printf '\n--- Build Tools layout ---\n'
        find "$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION" -maxdepth 2 \
            \( -type f -o -type l \) -print 2>/dev/null | sort || true

        printf '\n--- SDK licenses ---\n'
        cat "$SDK_ROOT/licenses/android-sdk-license" 2>&1 || true

        printf '\n--- Full build output ---\n'
        cat "$LIVE_LOG" 2>/dev/null || true
    } > "$FAIL_LOG"

    cp -f "$FAIL_LOG" "$STAMPED_FAIL_LOG" 2>/dev/null || true

    printf '\n\033[1;31m[HerDroid]\033[0m Build failed. Full report saved to:\n%s\n' "$FAIL_LOG" >&2
    printf 'Timestamped copy: %s\n' "$STAMPED_FAIL_LOG" >&2
}

main() {
    install_packages
    prepare_workspace
    configure_java
    accept_sdk_license
    install_platform
    configure_build_tools
    configure_project
    build_app "${1:-}"
}

run_logged() {
    local status

    prepare_output_dir

    rm -f "$FAIL_LOG" "$OUTPUT_APK"

    set +e
    (
        set -Eeuo pipefail
        main "$@"
    ) 2>&1 | tee "$LIVE_LOG"

    status=${PIPESTATUS[0]}
    set -e

    if (( status != 0 )); then
        write_failure_report "$status"
        exit "$status"
    fi

    rm -f "$LIVE_LOG"
}

run_logged "$@"
