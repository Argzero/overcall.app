#!/usr/bin/env bash
# Clones (if needed) the official Solana Mobile mobile-wallet-adapter
# repo, builds the bundled `fakewallet` reference app, and adb-installs
# it on the running emulator.
#
# fakewallet is a Solana Mobile-published reference MWA wallet — it
# generates a software keypair on first run, signs anything OverCall
# asks for, and lets us exercise the Connect Wallet → Register → on-
# chain transaction path end-to-end on a stock AVD without needing
# Phantom, Solflare, or a real Seeker Seed Vault.
#
# Idempotent: if tools/mwa/ already exists, just builds + installs.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$HERE/dev-env.sh"

ROOT="$HERE/.."
TOOLS="$ROOT/tools"
MWA_DIR="$TOOLS/mwa"

if ! adb -e devices | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "No adb -e device. Run ./scripts/start-emu.sh first." >&2
    exit 1
fi

mkdir -p "$TOOLS"
if [[ ! -d "$MWA_DIR/.git" ]]; then
    echo "==> Cloning solana-mobile/mobile-wallet-adapter..."
    git clone --depth 1 https://github.com/solana-mobile/mobile-wallet-adapter.git "$MWA_DIR"
fi

echo "==> Building fakewallet APK (this can take several minutes the first time)..."
( cd "$MWA_DIR/android" && ./gradlew :fakewallet:assembleV1Debug --no-daemon )

APK="$MWA_DIR/android/fakewallet/build/outputs/apk/v1/debug/fakewallet-v1-debug.apk"
if [[ ! -f "$APK" ]]; then
    echo "fakewallet APK not where expected: $APK" >&2
    exit 1
fi

echo "==> Installing fakewallet on emulator..."
adb -e install -r "$APK" >/dev/null

echo
echo "fakewallet installed. App package: com.solana.mobilewalletadapter.fakewallet"
echo
echo "Smoke-test the connect path:"
echo "  1. Launch OverCall on the emulator"
echo "  2. Tap Connect Wallet"
echo "  3. The MWA picker should pop fakewallet; tap it through and authorize"
