#!/usr/bin/env bash
# Pulls the attestation hash that WebHandoffActivity printed to logcat
# after a successful deep-link → Keystore attestation cycle. Lets you
# paste straight into the laptop web register page without aiming a
# webcam at the emulator window.
#
# Usage:
#   ./scripts/grab-attestation-hash.sh           # last 5 minutes of logs
#   ./scripts/grab-attestation-hash.sh --watch   # tail in real time
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$HERE/dev-env.sh"

WATCH=0
[[ "${1:-}" == "--watch" ]] && WATCH=1

TAG="OverCall/WebHandoff"
PATTERN='overcall://web-register-result?nonce='

extract() {
    # Grab the most recent return URI on stdin and print the hash field.
    awk -v pat="$PATTERN" '
        index($0, pat) {
            line = $0
        }
        END {
            if (line == "") {
                exit 1
            }
            # Pull out the hash= query param.
            n = index(line, "hash=")
            if (n == 0) exit 1
            tail = substr(line, n + 5)
            # Trim at any trailing whitespace or quotation.
            sub(/[ \t\r\n"\047].*/, "", tail)
            print tail
        }
    '
}

if [[ $WATCH == 1 ]]; then
    echo "Tailing logcat for $TAG — waiting for return URI..." >&2
    adb -e logcat -v raw -s "$TAG":I 2>/dev/null | while read -r line; do
        if [[ "$line" == *"$PATTERN"* ]]; then
            echo "$line" | extract
            exit 0
        fi
    done
else
    # -d = dump and exit. -t '5 minutes ago' isn't supported; just dump
    # everything for the tag and pick the last match.
    HASH="$(adb -e logcat -d -v raw -s "$TAG":I 2>/dev/null | extract || true)"
    if [[ -z "$HASH" ]]; then
        echo "No return URI found in logcat. Did you fire the deep link?" >&2
        echo "  ./scripts/deeplink-web-register.sh ... and tap Generate." >&2
        exit 1
    fi
    echo "$HASH"
fi
