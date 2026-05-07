/**
 * Lightweight E.164 normalizer for the web form. Strips spaces, dashes,
 * parens; ensures a single leading + and digits-only thereafter.
 *
 * The Android client uses libphonenumber for full validation. The web
 * doesn't need that level of rigor — the on-chain register instruction
 * re-validates with the same `+` + ASCII-digits rule and rejects anything
 * malformed.
 */
export function normalizeE164(input) {
    const trimmed = input.trim();
    if (!trimmed)
        return null;
    let body;
    if (trimmed.startsWith("+")) {
        body = trimmed.slice(1);
    }
    else {
        body = trimmed;
    }
    body = body.replace(/[\s\-()\.]/g, "");
    if (!/^\d{2,15}$/.test(body))
        return null;
    return "+" + body;
}
export function formatForDisplay(e164) {
    // Don't bring in libphonenumber for the laptop-side render — rough
    // group-of-3 spacing reads well enough for confirmation.
    if (!e164.startsWith("+"))
        return e164;
    const digits = e164.slice(1);
    if (digits.length <= 4)
        return e164;
    // crude groupings: country code (1–3) + national (rest in groups of 3)
    return "+" + digits.replace(/(\d{1,3})(\d{3})(\d{3,4})(\d*)/, "$1 $2 $3$4").trim();
}
