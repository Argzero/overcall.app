#!/usr/bin/env bash
# Sideload OverCall (and optionally fakewallet) onto any physical
# Android phone over USB. Device-agnostic — the only requirement is
# that the phone shows up in `adb devices` (USB debugging on, this
# laptop authorized).
#
# This is intentionally separate from the emulator path because:
#
#   • dev-env.sh pins ANDROID_SERIAL=emulator-5554 when an AVD is
#     running, which would steer `adb install` and `gradle install*`
#     at the wrong device. This script unsets it.
#   • we resolve the phone's serial dynamically (any non-emulator
#     device wins) so the script works regardless of vendor or model.
#   • OverCall installs as the MWA-capable devDebug variant (flip to
#     release when ready). fakewallet is optional — useful for a no-
#     stakes first run before letting a real wallet (Phantom /
#     Solflare / Backpack) sign anything. Skip with --no-fakewallet.
#
# Usage:
#   ./scripts/sideload-phone.sh                 # OverCall + fakewallet
#   ./scripts/sideload-phone.sh --no-fakewallet # OverCall only
#   ./scripts/sideload-phone.sh --serial <id>   # explicit device serial
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$HERE/dev-env.sh"

# dev-env.sh pins us at the emulator when one's running. Drop that
# pin so the rest of the script targets the physical device explicitly.
unset ANDROID_SERIAL

INSTALL_FAKEWALLET=1
TARGET_SERIAL=""
for arg in "$@"; do
    case "$arg" in
        --no-fakewallet)  INSTALL_FAKEWALLET=0 ;;
        --serial)         shift; TARGET_SERIAL="${1:-}" ;;
        --serial=*)       TARGET_SERIAL="${arg#--serial=}" ;;
        -h|--help)
            sed -n '1,/^set -euo/p' "$0" | sed 's/^# \?//'
            exit 0 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

if [[ -z "$TARGET_SERIAL" ]]; then
    # First non-emulator device line.
    TARGET_SERIAL="$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
fi
if [[ -z "$TARGET_SERIAL" ]]; then
    cat >&2 <<'EOF'
No physical device attached. Make sure the S23 is:

  • Plugged in via USB
  • Unlocked
  • Has USB debugging enabled (Developer options → USB debugging)
  • Has authorized this laptop (tap Allow on the phone's prompt)

Then re-run. `adb devices` should show your phone alongside any AVDs.
EOF
    exit 1
fi
echo "==> Targeting device: $TARGET_SERIAL"
adb -s "$TARGET_SERIAL" shell getprop ro.product.model 2>/dev/null | xargs -I{} echo "    model: {}"
adb -s "$TARGET_SERIAL" shell getprop ro.build.version.release 2>/dev/null | xargs -I{} echo "    Android: {}"

# --- 1) OverCall ----------------------------------------------------------
echo
echo "==> Building OverCall devDebug APK..."
( cd "$HERE/../android" && ANDROID_SERIAL="$TARGET_SERIAL" ./gradlew :app:installDevDebug )

echo
echo "==> Verifying OverCall is installed and the deep-link intent-filter parsed..."
QUERY_OUT="$(adb -s "$TARGET_SERIAL" shell cmd package query-activities \
    -a android.intent.action.VIEW \
    -c android.intent.category.BROWSABLE \
    -d 'overcall://web-register?phone=%2B14155550100&wallet=test&nonce=x' 2>&1)"
if echo "$QUERY_OUT" | grep -q "name=com.overcall.webhandoff.WebHandoffActivity" \
   && echo "$QUERY_OUT" | grep -q "packageName=com.overcall.dev"; then
    echo "    ✓ deep-link resolves to WebHandoffActivity"
else
    echo "    ✗ deep-link did NOT resolve — check AndroidManifest.xml" >&2
fi

# --- 2) fakewallet --------------------------------------------------------
if [[ "$INSTALL_FAKEWALLET" == 1 ]]; then
    ROOT="$HERE/.."
    TOOLS="$ROOT/tools"
    MWA_DIR="$TOOLS/mwa"

    mkdir -p "$TOOLS"
    if [[ ! -d "$MWA_DIR/.git" ]]; then
        echo
        echo "==> Cloning solana-mobile/mobile-wallet-adapter..."
        git clone --depth 1 https://github.com/solana-mobile/mobile-wallet-adapter.git "$MWA_DIR"
    fi

    APK="$MWA_DIR/android/fakewallet/build/outputs/apk/v1/debug/fakewallet-v1-debug.apk"
    if [[ ! -f "$APK" ]]; then
        echo
        echo "==> Building fakewallet APK (a few minutes the first time)..."
        ( cd "$MWA_DIR/android" && ./gradlew :fakewallet:assembleV1Debug --no-daemon )
    fi

    echo
    echo "==> Installing fakewallet on $TARGET_SERIAL..."
    adb -s "$TARGET_SERIAL" install -r "$APK" >/dev/null
    echo "    ✓ fakewallet installed (com.solana.mobilewalletadapter.fakewallet)"
fi

echo
echo "Done. Smoke-test on the S23:"
echo
echo "  1. Open OverCall."
echo "  2. Tap Connect Wallet."
echo "  3. The MWA picker should list any wallet that handles 'solana-wallet:' —"
echo "     fakewallet (if installed), Phantom, Solflare, Backpack, etc."
echo "  4. Pick fakewallet for a no-stakes round-trip first; pick your real"
echo "     wallet once you're comfortable the UI flow works."
echo
echo "Funding the connected pubkey on devnet (if needed):"
echo "  solana transfer <pubkey> 0.05 --url devnet --allow-unfunded-recipient"
