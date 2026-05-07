import bs58 from "bs58";
const SCHEME = "overcall://";
/**
 * Outbound URI the laptop renders as a QR. Phone scans, validates SIM
 * matches `phone`, generates a Keystore attestation bound to (phone,
 * wallet), and posts the hash back via [parseHandoffResult].
 */
export function buildHandoffUri(payload) {
    const params = new URLSearchParams({
        phone: payload.phoneE164,
        wallet: payload.wallet.toBase58(),
        nonce: payload.nonce,
    });
    return `${SCHEME}web-register?${params.toString()}`;
}
/**
 * Inbound URI the phone produces. Laptop scans (or user pastes) to
 * recover the attestation hash.
 */
export function parseHandoffResult(uri) {
    const expected = `${SCHEME}web-register-result?`;
    if (!uri.startsWith(expected))
        return null;
    const params = new URLSearchParams(uri.slice(expected.length));
    const nonce = params.get("nonce");
    const hash = params.get("hash");
    if (!nonce || !hash)
        return null;
    return { nonce, attestationHashBase58: hash };
}
export function generateNonce() {
    const bytes = new Uint8Array(12);
    crypto.getRandomValues(bytes);
    return bs58.encode(bytes);
}
export function attestationHashFromBase58(b58) {
    const bytes = bs58.decode(b58);
    if (bytes.length !== 32) {
        throw new Error(`attestation hash must be 32 bytes, got ${bytes.length}`);
    }
    return bytes;
}
