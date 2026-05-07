#!/usr/bin/env bash
# Fire an overcall://web-register?... deep link straight at the AVD,
# skipping the laptop-camera→phone QR-scan step (which is awkward inside
# an emulator). Equivalent to the user scanning the laptop-side QR with
# their phone's camera.
#
# Usage:
#   ./scripts/deeplink-web-register.sh '+15551234567' <wallet-base58> <nonce>
#
# Where the wallet-base58 is what your laptop's web wallet returned (open
# the web register page, copy from the QR's URI under the "URI (advanced)"
# disclosure if you don't want to OCR it). Nonce is any short string —
# whatever the web page generated this session.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$HERE/dev-env.sh"

if [[ $# -lt 3 ]]; then
    echo "usage: $0 <phone-e164> <wallet-base58> <nonce>" >&2
    exit 2
fi
PHONE="$1"
WALLET="$2"
NONCE="$3"

# URL-encode '+' explicitly — adb's Intent dispatcher doesn't always do it.
PHONE_ENC="${PHONE//+/%2B}"

URI="overcall://web-register?phone=${PHONE_ENC}&wallet=${WALLET}&nonce=${NONCE}"
echo "Firing: $URI"

adb -e shell am start -W \
    -a android.intent.action.VIEW \
    -c android.intent.category.BROWSABLE \
    -d "'$URI'" \
    com.overcall.dev

echo
echo "Then on the device, tap 'Generate attestation'. To grab the result:"
echo "  ./scripts/grab-attestation-hash.sh"
