#!/usr/bin/env bash
# Idempotent Android emulator + system-image install + AVD creation.
# Re-running it is safe: it skips already-installed packages and reuses
# an existing AVD if one with the same name is found.
#
# Downloads ~700 MB on first run (the system image dominates).

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$HERE/dev-env.sh"

echo "Using ANDROID_HOME=$ANDROID_HOME"
echo "Using JAVA_HOME=$JAVA_HOME"
echo "Using system image: $OVERCALL_SYSIMAGE"
echo "Using AVD name: $OVERCALL_AVD"
echo

# --- Resolve a usable sdkmanager + avdmanager ----------------------------
# In a stock Android Studio install we don't have cmdline-tools/latest yet,
# but Homebrew ships standalone copies in /opt/homebrew/bin. We always pass
# --sdk_root so they install into the canonical $ANDROID_HOME.
SDKMANAGER="$(command -v sdkmanager || true)"
AVDMANAGER="$(command -v avdmanager || true)"
if [[ -z "$SDKMANAGER" || -z "$AVDMANAGER" ]]; then
    echo "error: sdkmanager / avdmanager not on PATH." >&2
    echo "Install via Homebrew:  brew install --cask android-commandlinetools" >&2
    exit 1
fi

SDK_FLAG=("--sdk_root=$ANDROID_HOME")

accept_licenses() {
    yes 2>/dev/null | "$SDKMANAGER" "${SDK_FLAG[@]}" --licenses >/dev/null 2>&1 || true
}

# --- Install platform-tools, emulator, system image ----------------------
echo "==> Accepting any pending SDK licenses..."
accept_licenses

echo "==> Installing platform-tools, emulator, $OVERCALL_SYSIMAGE..."
"$SDKMANAGER" "${SDK_FLAG[@]}" \
    "platform-tools" \
    "emulator" \
    "$OVERCALL_SYSIMAGE" \
    >/dev/null

# --- Create the AVD if it doesn't already exist --------------------------
if "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: ${OVERCALL_AVD}\$"; then
    echo "==> AVD ${OVERCALL_AVD} already exists — leaving it in place."
else
    echo "==> Creating AVD ${OVERCALL_AVD}..."
    echo "no" | "$AVDMANAGER" create avd \
        --name "$OVERCALL_AVD" \
        --package "$OVERCALL_SYSIMAGE" \
        --device "pixel_6" \
        --force \
        >/dev/null
fi

# --- Tweak AVD config for OverCall's needs (telephony, storage, ram) -----
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}/${OVERCALL_AVD}.avd"
CONFIG="$AVD_HOME/config.ini"
if [[ ! -f "$CONFIG" ]]; then
    echo "error: AVD config.ini not found at $CONFIG" >&2
    exit 1
fi

# Idempotent kv setter — replaces or appends a single key.
set_kv() {
    local key="$1" value="$2"
    if grep -q "^${key}=" "$CONFIG"; then
        # macOS sed -i wants an empty backup arg
        sed -i '' "s|^${key}=.*|${key}=${value}|" "$CONFIG"
    else
        printf '%s=%s\n' "$key" "$value" >> "$CONFIG"
    fi
}

set_kv hw.gps               yes
set_kv hw.gpu.enabled       yes
set_kv hw.gpu.mode          auto
set_kv hw.ramSize           4096
set_kv vm.heapSize          512
set_kv disk.dataPartition.size 8192M
set_kv hw.keyboard          yes
set_kv hw.camera.back       virtualscene
set_kv hw.camera.front      emulated
set_kv showDeviceFrame      no

echo "==> AVD ready at $AVD_HOME"
echo
echo "Next: ./scripts/start-emu.sh   (boots the AVD)"
echo "      ./scripts/install-app.sh (builds + installs dev flavor)"
