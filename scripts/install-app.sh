#!/usr/bin/env bash
# Builds and installs the dev flavor on the running AVD (or any single
# attached device). Quick smoke-test that the deep-link intent-filter
# actually parsed by Android by querying the package manager.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$HERE/dev-env.sh"

DEV_COUNT="$(adb -e devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
if [[ "$DEV_COUNT" -eq 0 ]]; then
    echo "No adb -e device attached. Run ./scripts/start-emu.sh first." >&2
    exit 1
fi

cd "$HERE/../android"
./gradlew :app:installDevDebug

echo
echo "==> Verifying overcall://web-register intent-filter is registered..."
QUERY_OUT="$(adb -e shell cmd package query-activities \
    -a android.intent.action.VIEW \
    -c android.intent.category.BROWSABLE \
    -d "overcall://web-register?phone=%2B15551234567&wallet=test&nonce=x" 2>&1)"
if echo "$QUERY_OUT" | grep -q "name=com.overcall.webhandoff.WebHandoffActivity" \
   && echo "$QUERY_OUT" | grep -q "packageName=com.overcall.dev"; then
    echo "    ✓ deep-link resolves to WebHandoffActivity"
else
    echo "    ✗ deep-link did NOT resolve — check AndroidManifest.xml" >&2
    echo "$QUERY_OUT" | head -5 >&2
fi

echo
echo "App installed. Launch it manually or fire a deep link:"
echo "  ./scripts/deeplink-web-register.sh '+15551234567' <wallet-base58> <nonce>"
