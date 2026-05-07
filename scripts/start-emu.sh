#!/usr/bin/env bash
# Boots the OverCall AVD headed and waits for boot_completed.
# If the AVD is already running, just attaches to it.
#
# Usage:
#   ./scripts/start-emu.sh                  # default OVERCALL_AVD, headed
#   ./scripts/start-emu.sh --headless       # no UI window
#   ./scripts/start-emu.sh --wipe-data      # cold-boot, fresh state
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$HERE/dev-env.sh"

HEADLESS=0
WIPE=0
for arg in "$@"; do
    case "$arg" in
        --headless) HEADLESS=1 ;;
        --wipe-data) WIPE=1 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

# If a device is already attached, just print and return.
if adb -e devices | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "Emulator already running:"
    adb -e devices
    exit 0
fi

if ! emulator -list-avds | grep -qx "$OVERCALL_AVD"; then
    echo "AVD not found: $OVERCALL_AVD" >&2
    echo "Run: ./scripts/setup-android-emu.sh" >&2
    exit 1
fi

ARGS=(-avd "$OVERCALL_AVD" -no-snapshot-save -netfast)
[[ $HEADLESS == 1 ]] && ARGS+=(-no-window -no-audio -no-boot-anim)
[[ $WIPE == 1 ]] && ARGS+=(-wipe-data)

LOG="$HERE/../.emulator.log"
echo "Booting $OVERCALL_AVD ... (logs: $LOG)"
nohup emulator "${ARGS[@]}" >"$LOG" 2>&1 &
EMU_PID=$!
echo "  emulator pid: $EMU_PID"

# Best-effort: bring the qemu window to the front once Qt has spun up.
# Silently no-ops if osascript lacks accessibility permission, or if the
# user is running headless.
if [[ $HEADLESS == 0 ]]; then
    (
        sleep 8
        osascript -e 'tell application "System Events" to set frontmost of (every process whose name is "qemu-system-aarch64") to true' \
            >/dev/null 2>&1 || true
    ) &
fi

# Wait for ADB to see the device.
echo -n "Waiting for adb -e device ..."
for _ in $(seq 1 60); do
    if adb -e devices | awk 'NR>1 && $2=="device"' | grep -q .; then
        echo " up."
        break
    fi
    echo -n "."
    sleep 1
done

# Wait for full boot.
echo -n "Waiting for sys.boot_completed ..."
for _ in $(seq 1 120); do
    if [[ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        echo " booted."
        break
    fi
    echo -n "."
    sleep 1
done

adb -e shell input keyevent 82 >/dev/null 2>&1 || true   # unlock keyguard
echo
echo "Ready."
echo "  install app:  ./scripts/install-app.sh"
echo "  fire deep:    ./scripts/deeplink-web-register.sh +15551234567 <wallet> <nonce>"
