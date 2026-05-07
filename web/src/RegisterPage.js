import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useMemo, useState } from "react";
import { useConnection, useWallet } from "@solana/wallet-adapter-react";
import { WalletMultiButton } from "@solana/wallet-adapter-react-ui";
import { Transaction } from "@solana/web3.js";
import { QRCodeSVG } from "qrcode.react";
import { ATTESTATION_KIND } from "./lib/config";
import { attestationHashFromBase58, buildHandoffUri, generateNonce, parseHandoffResult, } from "./lib/handoff";
import { formatForDisplay, normalizeE164 } from "./lib/phone";
import { buildRegisterWithSolFeeIx } from "./lib/registerIx";
import { fetchRegistryConfig } from "./lib/registryConfig";
import { QrScanner } from "./QrScanner";
export function RegisterPage() {
    const { connection } = useConnection();
    const wallet = useWallet();
    const [phoneInput, setPhoneInput] = useState("");
    const [hashInput, setHashInput] = useState("");
    const [nonce] = useState(generateNonce);
    const [phase, setPhase] = useState({ kind: "idle" });
    const [scannerOpen, setScannerOpen] = useState(false);
    // Auto-fill the hash field when the user scans the phone's return QR.
    // Validates that the embedded nonce matches the one we're showing in
    // the outbound QR — defends against accidental cross-session paste.
    function onScan(uri) {
        const result = parseHandoffResult(uri);
        if (!result) {
            setPhase({ kind: "error", message: "Scanned QR isn't a valid OverCall result." });
            return;
        }
        if (result.nonce !== nonce) {
            setPhase({
                kind: "error",
                message: "Scanned attestation is from a different session. Refresh and try again.",
            });
            return;
        }
        setHashInput(result.attestationHashBase58);
        setScannerOpen(false);
        setPhase({ kind: "idle" });
    }
    const e164 = useMemo(() => normalizeE164(phoneInput), [phoneInput]);
    const handoffUri = useMemo(() => {
        if (!e164 || !wallet.publicKey)
            return null;
        return buildHandoffUri({ phoneE164: e164, wallet: wallet.publicKey, nonce });
    }, [e164, wallet.publicKey, nonce]);
    async function onSubmit() {
        if (!wallet.publicKey || !wallet.signTransaction) {
            setPhase({ kind: "error", message: "Connect a wallet first." });
            return;
        }
        if (!e164) {
            setPhase({ kind: "error", message: "Phone number isn't a valid E.164." });
            return;
        }
        let attestationHash;
        try {
            attestationHash = attestationHashFromBase58(hashInput.trim());
        }
        catch (e) {
            setPhase({
                kind: "error",
                message: e instanceof Error ? e.message : "Bad attestation hash",
            });
            return;
        }
        setPhase({ kind: "submitting" });
        try {
            const config = await fetchRegistryConfig(connection);
            const ix = buildRegisterWithSolFeeIx({
                phoneE164: e164,
                owner: wallet.publicKey,
                treasury: config.treasury,
                attestationHash,
                attestationKind: ATTESTATION_KIND.WEB_DELEGATED,
            });
            const { blockhash } = await connection.getLatestBlockhash("confirmed");
            const tx = new Transaction();
            tx.add(ix);
            tx.feePayer = wallet.publicKey;
            tx.recentBlockhash = blockhash;
            const signed = await wallet.signTransaction(tx);
            const signature = await connection.sendRawTransaction(signed.serialize());
            await connection.confirmTransaction(signature, "confirmed");
            setPhase({ kind: "success", signature });
        }
        catch (e) {
            setPhase({
                kind: "error",
                message: e instanceof Error ? e.message : String(e),
            });
        }
    }
    return (_jsxs("div", { className: "min-h-screen flex flex-col items-center text-coingold", children: [_jsxs("header", { className: "w-full max-w-3xl px-6 pt-10 pb-6 flex items-center justify-between", children: [_jsxs("div", { className: "flex items-center gap-3", children: [_jsx("img", { src: "/icon.png", alt: "OverCall", className: "w-12 h-12 rounded-2xl" }), _jsxs("div", { children: [_jsx("h1", { className: "text-3xl font-black", children: "OverCall" }), _jsx("p", { className: "text-muted text-sm", children: "Pay-by-phone for Solana" })] })] }), _jsx(WalletMultiButton, {})] }), _jsxs("main", { className: "w-full max-w-3xl px-6 pb-16 flex flex-col gap-8", children: [_jsxs("section", { className: "rounded-3xl bg-black/30 border border-coinrim/40 p-6", children: [_jsx("h2", { className: "text-xl font-bold mb-2", children: "Register your phone" }), _jsx("p", { className: "text-muted text-sm mb-4", children: "Hardware-attested. Your laptop wallet pays the fee; your phone proves it owns the SIM via Android Keystore." }), _jsx("label", { className: "block text-sm mt-2 mb-1", children: "Phone (E.164)" }), _jsx("input", { value: phoneInput, onChange: (e) => setPhoneInput(e.target.value), placeholder: "+15551234567", className: "w-full bg-black/40 border border-coinrim/40 rounded-xl px-4 py-3 text-coingold placeholder-muted/60 focus:outline-none focus:border-coingold" }), phoneInput && !e164 && (_jsx("p", { className: "text-red-300 text-sm mt-1", children: "Not a valid E.164 \u2014 start with + and country code, digits only." })), e164 && (_jsxs("p", { className: "text-muted text-sm mt-1", children: ["Will register as ", _jsx("span", { className: "text-coingold", children: formatForDisplay(e164) })] }))] }), _jsxs("section", { className: "rounded-3xl bg-black/30 border border-coinrim/40 p-6", children: [_jsx("h2", { className: "text-xl font-bold mb-2", children: "1 \u00B7 Scan with your phone" }), _jsxs("p", { className: "text-muted text-sm mb-4", children: ["Open OverCall on your phone, go to ", _jsx("strong", { className: "text-coingold", children: "Settings \u2192 Web Register" }), ", and scan this QR. Your phone reads its SIM, generates a hardware attestation bound to ", _jsx("em", { children: "this" }), " wallet, and shows you a return QR."] }), handoffUri ? (_jsx("div", { className: "bg-white rounded-2xl p-4 inline-block", children: _jsx(QRCodeSVG, { value: handoffUri, size: 240, level: "M" }) })) : (_jsx("p", { className: "text-muted italic", children: wallet.publicKey
                                    ? "Enter a phone number above to generate the handoff QR."
                                    : "Connect a wallet first." })), handoffUri && (_jsxs("details", { className: "mt-3", children: [_jsx("summary", { className: "text-muted text-sm cursor-pointer", children: "URI (advanced)" }), _jsx("code", { className: "block mt-2 text-xs break-all text-muted", children: handoffUri })] }))] }), _jsxs("section", { className: "rounded-3xl bg-black/30 border border-coinrim/40 p-6", children: [_jsx("h2", { className: "text-xl font-bold mb-2", children: "2 \u00B7 Bring back the attestation" }), _jsx("p", { className: "text-muted text-sm mb-4", children: "Either scan your phone's return QR with this laptop's camera, or paste the attestation hash your phone displayed." }), _jsx("div", { className: "flex gap-3 mb-3", children: _jsx("button", { onClick: () => setScannerOpen(true), className: "bg-coingold text-midnight font-bold rounded-full px-5 py-2 hover:opacity-90", children: "Scan return QR" }) }), _jsx("label", { className: "block text-sm mb-1", children: "Attestation hash (base58)" }), _jsx("input", { value: hashInput, onChange: (e) => setHashInput(e.target.value), placeholder: "3uy6tFq8\u2026", className: "w-full bg-black/40 border border-coinrim/40 rounded-xl px-4 py-3 text-coingold placeholder-muted/60 focus:outline-none focus:border-coingold" })] }), _jsxs("section", { className: "rounded-3xl bg-black/30 border border-coinrim/40 p-6", children: [_jsx("h2", { className: "text-xl font-bold mb-2", children: "3 \u00B7 Sign + submit" }), _jsx("button", { disabled: !wallet.publicKey || !e164 || !hashInput || phase.kind === "submitting", onClick: onSubmit, className: "bg-coingold text-midnight font-bold rounded-full px-6 py-3 hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed", children: phase.kind === "submitting" ? "Submitting…" : "Register on devnet" }), phase.kind === "success" && (_jsxs("div", { className: "mt-4 text-sm", children: [_jsx("p", { className: "text-coingold", children: "Registered \u2713" }), _jsxs("p", { className: "text-muted", children: ["Tx:", " ", _jsxs("a", { className: "underline", href: `https://solscan.io/tx/${phase.signature}?cluster=devnet`, target: "_blank", rel: "noreferrer", children: [phase.signature.slice(0, 12), "\u2026"] })] })] })), phase.kind === "error" && (_jsxs("p", { className: "mt-4 text-red-300 text-sm", children: ["Failed: ", phase.message] }))] })] }), scannerOpen && (_jsx(QrScanner, { onResult: onScan, onClose: () => setScannerOpen(false) }))] }));
}
