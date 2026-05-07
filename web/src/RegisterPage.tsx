import { useMemo, useState } from "react";
import { useConnection, useWallet } from "@solana/wallet-adapter-react";
import { WalletMultiButton } from "@solana/wallet-adapter-react-ui";
import { Transaction } from "@solana/web3.js";
import { QRCodeSVG } from "qrcode.react";
import { ATTESTATION_KIND } from "./lib/config";
import {
  attestationHashFromBase58,
  buildHandoffUri,
  generateNonce,
  parseHandoffResult,
} from "./lib/handoff";
import { formatForDisplay, normalizeE164 } from "./lib/phone";
import { buildRegisterWithSolFeeIx } from "./lib/registerIx";
import { fetchRegistryConfig } from "./lib/registryConfig";
import { QrScanner } from "./QrScanner";

type Phase =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success"; signature: string }
  | { kind: "error"; message: string };

export function RegisterPage() {
  const { connection } = useConnection();
  const wallet = useWallet();

  const [phoneInput, setPhoneInput] = useState("");
  const [hashInput, setHashInput] = useState("");
  const [nonce] = useState(generateNonce);
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const [scannerOpen, setScannerOpen] = useState(false);

  // Auto-fill the hash field when the user scans the phone's return QR.
  // Validates that the embedded nonce matches the one we're showing in
  // the outbound QR — defends against accidental cross-session paste.
  function onScan(uri: string) {
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
    if (!e164 || !wallet.publicKey) return null;
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
    let attestationHash: Uint8Array;
    try {
      attestationHash = attestationHashFromBase58(hashInput.trim());
    } catch (e) {
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
    } catch (e) {
      setPhase({
        kind: "error",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  }

  return (
    <div className="min-h-screen flex flex-col items-center text-coingold">
      <header className="w-full max-w-3xl px-6 pt-10 pb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <img src="/icon.png" alt="OverCall" className="w-12 h-12 rounded-2xl" />
          <div>
            <h1 className="text-3xl font-black">OverCall</h1>
            <p className="text-muted text-sm">Pay-by-phone for Solana</p>
          </div>
        </div>
        <WalletMultiButton />
      </header>

      <main className="w-full max-w-3xl px-6 pb-16 flex flex-col gap-8">
        <section className="rounded-3xl bg-black/30 border border-coinrim/40 p-6">
          <h2 className="text-xl font-bold mb-2">Register your phone</h2>
          <p className="text-muted text-sm mb-4">
            Hardware-attested. Your laptop wallet pays the fee; your phone proves
            it owns the SIM via Android Keystore.
          </p>

          <label className="block text-sm mt-2 mb-1">Phone (E.164)</label>
          <input
            value={phoneInput}
            onChange={(e) => setPhoneInput(e.target.value)}
            placeholder="+15551234567"
            className="w-full bg-black/40 border border-coinrim/40 rounded-xl px-4 py-3 text-coingold placeholder-muted/60 focus:outline-none focus:border-coingold"
          />
          {phoneInput && !e164 && (
            <p className="text-red-300 text-sm mt-1">
              Not a valid E.164 — start with + and country code, digits only.
            </p>
          )}
          {e164 && (
            <p className="text-muted text-sm mt-1">
              Will register as <span className="text-coingold">{formatForDisplay(e164)}</span>
            </p>
          )}
        </section>

        <section className="rounded-3xl bg-black/30 border border-coinrim/40 p-6">
          <h2 className="text-xl font-bold mb-2">1 · Scan with your phone</h2>
          <p className="text-muted text-sm mb-4">
            Open OverCall on your phone, go to <strong className="text-coingold">Settings →
            Web Register</strong>, and scan this QR. Your phone reads its SIM, generates a
            hardware attestation bound to <em>this</em> wallet, and shows you a return QR.
          </p>

          {handoffUri ? (
            <div className="bg-white rounded-2xl p-4 inline-block">
              <QRCodeSVG value={handoffUri} size={240} level="M" />
            </div>
          ) : (
            <p className="text-muted italic">
              {wallet.publicKey
                ? "Enter a phone number above to generate the handoff QR."
                : "Connect a wallet first."}
            </p>
          )}

          {handoffUri && (
            <details className="mt-3">
              <summary className="text-muted text-sm cursor-pointer">URI (advanced)</summary>
              <code className="block mt-2 text-xs break-all text-muted">{handoffUri}</code>
            </details>
          )}
        </section>

        <section className="rounded-3xl bg-black/30 border border-coinrim/40 p-6">
          <h2 className="text-xl font-bold mb-2">2 · Bring back the attestation</h2>
          <p className="text-muted text-sm mb-4">
            Either scan your phone's return QR with this laptop's camera, or
            paste the attestation hash your phone displayed.
          </p>

          <div className="flex gap-3 mb-3">
            <button
              onClick={() => setScannerOpen(true)}
              className="bg-coingold text-midnight font-bold rounded-full px-5 py-2 hover:opacity-90"
            >
              Scan return QR
            </button>
          </div>

          <label className="block text-sm mb-1">Attestation hash (base58)</label>
          <input
            value={hashInput}
            onChange={(e) => setHashInput(e.target.value)}
            placeholder="3uy6tFq8…"
            className="w-full bg-black/40 border border-coinrim/40 rounded-xl px-4 py-3 text-coingold placeholder-muted/60 focus:outline-none focus:border-coingold"
          />
        </section>

        <section className="rounded-3xl bg-black/30 border border-coinrim/40 p-6">
          <h2 className="text-xl font-bold mb-2">3 · Sign + submit</h2>
          <button
            disabled={!wallet.publicKey || !e164 || !hashInput || phase.kind === "submitting"}
            onClick={onSubmit}
            className="bg-coingold text-midnight font-bold rounded-full px-6 py-3 hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {phase.kind === "submitting" ? "Submitting…" : "Register on devnet"}
          </button>

          {phase.kind === "success" && (
            <div className="mt-4 text-sm">
              <p className="text-coingold">Registered ✓</p>
              <p className="text-muted">
                Tx:{" "}
                <a
                  className="underline"
                  href={`https://solscan.io/tx/${phase.signature}?cluster=devnet`}
                  target="_blank"
                  rel="noreferrer"
                >
                  {phase.signature.slice(0, 12)}…
                </a>
              </p>
            </div>
          )}
          {phase.kind === "error" && (
            <p className="mt-4 text-red-300 text-sm">Failed: {phase.message}</p>
          )}
        </section>
      </main>

      {scannerOpen && (
        <QrScanner
          onResult={onScan}
          onClose={() => setScannerOpen(false)}
        />
      )}
    </div>
  );
}
