#!/usr/bin/env bash
# Source this file (don't execute) — it exports env vars and PATH tweaks
# every other dev script in this repo expects.
#
#   source scripts/dev-env.sh
#
# Idempotent: re-sourcing won't double-stack PATH entries.

# --- Java (Android Studio's bundled JBR is what builds Android against) ----
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "dev-env: warning — JAVA_HOME doesn't have bin/java at: $JAVA_HOME" >&2
fi

# --- Android SDK ----------------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ ! -d "$ANDROID_HOME" ]]; then
    echo "dev-env: warning — ANDROID_HOME directory does not exist: $ANDROID_HOME" >&2
fi

# --- PATH augmentation (only add entries that aren't already there) ------
overcall_pathadd() {
    local entry="$1"
    case ":$PATH:" in
        *":$entry:"*) ;;
        *) PATH="$entry:$PATH" ;;
    esac
}
overcall_pathadd "$JAVA_HOME/bin"
overcall_pathadd "$ANDROID_HOME/platform-tools"
overcall_pathadd "$ANDROID_HOME/emulator"
overcall_pathadd "$ANDROID_HOME/cmdline-tools/latest/bin"
unset -f overcall_pathadd
export PATH

# Silence the harmless cmdline-tools `test: : integer expression expected`
# warning. The test is a JDK version check on JDK <17; we run JDK 21 from
# Android Studio's bundled JBR.
export SKIP_JDK_VERSION_CHECK="${SKIP_JDK_VERSION_CHECK:-1}"

# --- AVD name shared across scripts --------------------------------------
export OVERCALL_AVD="${OVERCALL_AVD:-overcall_dev}"

# --- Convenience flag — the SDK image we expect on the AVD ---------------
# arm64 host (Apple Silicon) → arm64 image; Intel Mac → x86_64.
case "$(uname -m)" in
    arm64) export OVERCALL_EMU_ARCH="arm64-v8a" ;;
    *)     export OVERCALL_EMU_ARCH="x86_64" ;;
esac
export OVERCALL_API="${OVERCALL_API:-35}"
export OVERCALL_SYSIMAGE="system-images;android-${OVERCALL_API};google_apis;${OVERCALL_EMU_ARCH}"

# When both an emulator and a physical device (S23 / Seeker) are
# plugged in, ambiguous adb/gradle commands fail. Pin everything to the
# running emulator by exporting ANDROID_SERIAL — `gradle :app:install*`
# respects this, as do all bare `adb` invocations. Falls through silently
# if no emulator is running.
if [[ -z "${ANDROID_SERIAL:-}" ]] && command -v adb >/dev/null 2>&1; then
    overcall_emu_serial="$(adb devices 2>/dev/null | awk '/^emulator-[0-9]+\tdevice$/ {print $1; exit}')"
    if [[ -n "$overcall_emu_serial" ]]; then
        export ANDROID_SERIAL="$overcall_emu_serial"
    fi
    unset overcall_emu_serial
fi
