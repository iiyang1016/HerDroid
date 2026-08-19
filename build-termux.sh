#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android-sdk-termux}}"
PLATFORM_VERSION="37.0"
PLATFORM_ZIP="platform-37.0_r01.zip"
PLATFORM_SHA256="19bdcf42de0cb0e9500a27da6833fc30cdd49a7ec690aa5cabaa0ef893af9ebe"
PLATFORM_URL="https://dl.google.com/android/repository/${PLATFORM_ZIP}"
BUILD_TOOLS_VERSION="36.0.0"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
RUN_ID="$(date '+%Y%m%d-%H%M%S')"
LIVE_LOG="$PROJECT_DIR/.herdroid-build-$RUN_ID.log"
FAIL_LOG="$PROJECT_DIR/herdroid-build-failed.txt"
STAMPED_FAIL_LOG="$PROJECT_DIR/herdroid-build-failed-$RUN_ID.txt"
DOWNLOADS_DIR="$HOME/storage/downloads"

log() { printf '\n\033[1;34m[HerDroid]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[HerDroid]\033[0m %s\n' "$*" >&2; exit 1; }

if [[ "${PREFIX:-}" != *com.termux* ]]; then
    die "Run this script inside the official Termux app environment."
fi

if [[ "$PROJECT_DIR" == /storage/* || "$PROJECT_DIR" == /sdcard/* ]]; then
    die "Move/clone HerDroid into Termux home first (for example: ~/HerDroid). Android shared storage is unsuitable for Gradle builds."
fi

install_packages() {
    local packages=(openjdk-17 gradle aapt curl unzip ca-certificates)
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
    log "Using Gradle $gradle_version and $(aapt2 version 2>&1 | head -n 1)"
}

install_platform() {
    local platform_dir="$SDK_ROOT/platforms/android-$PLATFORM_VERSION"
    if [[ -f "$platform_dir/android.jar" ]]; then
        log "Android SDK Platform $PLATFORM_VERSION already installed"
        return
    fi

    log "Installing Android SDK Platform $PLATFORM_VERSION"
    mkdir -p "$SDK_ROOT/platforms"

    local tmp
    tmp="$(mktemp -d)"

    curl -fL --retry 3 --retry-delay 2 "$PLATFORM_URL" -o "$tmp/$PLATFORM_ZIP"
    printf '%s  %s\n' "$PLATFORM_SHA256" "$tmp/$PLATFORM_ZIP" | sha256sum -c -
    unzip -q "$tmp/$PLATFORM_ZIP" -d "$SDK_ROOT/platforms"

    if [[ ! -f "$platform_dir/android.jar" ]]; then
        local found
        found="$(find "$SDK_ROOT/platforms" -maxdepth 2 -type f -name android.jar -path '*37*' -print -quit || true)"
        [[ -n "$found" ]] || die "Android 37 platform archive extracted, but android.jar was not found."
        mkdir -p "$platform_dir"
        cp -a "$(dirname "$found")/." "$platform_dir/"
    fi

    rm -rf "$tmp"
}

configure_build_tools() {
    local bt="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
    log "Preparing Termux-native Android build-tools shim"
    mkdir -p "$bt"

    for tool in aapt aapt2 aidl zipalign; do
        if command -v "$tool" >/dev/null 2>&1; then
            ln -sf "$(command -v "$tool")" "$bt/$tool"
        fi
    done

    cat > "$bt/source.properties" <<PROPS
Pkg.Desc=Android SDK Build-Tools $BUILD_TOOLS_VERSION (Termux native shim)
Pkg.Revision=$BUILD_TOOLS_VERSION
PROPS

    [[ -x "$bt/aapt2" ]] || die "Termux aapt2 is unavailable. Try: pkg reinstall aapt"
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

build_app() {
    local heap
    heap="$(choose_heap)"
    log "Building HerDroid debug APK (heap: $heap, one worker)"

    cd "$PROJECT_DIR"

    if [[ "${1:-}" == "--clean" ]]; then
        gradle :app:clean \
            --no-daemon \
            --max-workers=1 \
            --no-parallel \
            --no-configuration-cache \
            --stacktrace \
            --info \
            -Dorg.gradle.jvmargs="-Xmx$heap -Dfile.encoding=UTF-8" \
            -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2"
    fi

    gradle :app:assembleDebug \
        --no-daemon \
        --max-workers=1 \
        --no-parallel \
        --no-configuration-cache \
        --stacktrace \
        --info \
        --warning-mode all \
        -Dorg.gradle.jvmargs="-Xmx$heap -Dfile.encoding=UTF-8" \
        -Pkotlin.compiler.execution.strategy=in-process \
        -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2"

    [[ -f "$APK_PATH" ]] || die "Gradle finished but the expected APK was not found: $APK_PATH"
    log "Build complete"
    printf '\nAPK: %s\n' "$APK_PATH"
}

write_failure_report() {
    local status="$1"
    {
        printf 'HerDroid Termux build failure report\n'
        printf '===================================\n'
        printf 'Exit code: %s\n' "$status"
        printf 'Timestamp: %s\n' "$(date -Iseconds 2>/dev/null || date)"
        printf 'Project: %s\n' "$PROJECT_DIR"
        printf 'SDK root: %s\n' "$SDK_ROOT"
        printf 'PREFIX: %s\n' "${PREFIX:-unset}"
        printf '\n--- Device / environment ---\n'
        uname -a 2>&1 || true
        if command -v termux-info >/dev/null 2>&1; then
            termux-info 2>&1 || true
        fi
        printf '\n--- Java ---\n'
        java -version 2>&1 || true
        printf '\n--- Gradle ---\n'
        gradle --version 2>&1 || true
        printf '\n--- Android tools ---\n'
        aapt2 version 2>&1 || true
        printf '\n--- Full build output ---\n'
        cat "$LIVE_LOG" 2>/dev/null || true
    } > "$FAIL_LOG"

    cp -f "$FAIL_LOG" "$STAMPED_FAIL_LOG" 2>/dev/null || true

    printf '\n\033[1;31m[HerDroid]\033[0m Build failed. Full report saved to:\n%s\n' "$FAIL_LOG" >&2
    printf 'Timestamped copy: %s\n' "$STAMPED_FAIL_LOG" >&2

    if [[ -d "$DOWNLOADS_DIR" && -w "$DOWNLOADS_DIR" ]]; then
        local download_copy="$DOWNLOADS_DIR/HerDroid-build-failed-$RUN_ID.txt"
        cp -f "$FAIL_LOG" "$download_copy" 2>/dev/null || true
        if [[ -f "$download_copy" ]]; then
            printf 'Downloads copy: %s\n' "$download_copy" >&2
        fi
    else
        printf 'Tip: run termux-setup-storage once if you want an automatic copy in Downloads.\n' >&2
    fi
}

main() {
    install_packages
    configure_java
    install_platform
    configure_build_tools
    configure_project
    build_app "${1:-}"
}

run_logged() {
    local status
    rm -f "$FAIL_LOG"

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
