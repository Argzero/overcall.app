# OverCall — Web register portal

A static React app that lets a user with a web wallet (Phantom / Backpack /
Solflare) register their phone number on-chain by handing off the hardware
attestation to their phone via QR.

## Flow

```
   Laptop                                  Phone
─────────────────────────────────────────────────────────────────────────
  Connect wallet                           OverCall app installed
  Enter phone E.164                        Settings → Web Register
  ┌───────────────────────────┐
  │  QR  overcall://web-       │  ─── scan ───▶
  │  register?phone=…&wallet=…│            Reads SIM, validates
  │  &nonce=…                  │            the requested phone matches.
  └───────────────────────────┘            Generates a Keystore key
                                           with attestation challenge
                                           sha256(phone || wallet).
                                           Returns a QR:
                                            overcall://web-register-result
                                            ?nonce=…&hash=<base58>
  ┌───────────────────────────┐
  │  Camera ◀── scan back     │  ◀── show ───
  └───────────────────────────┘

  Hash flows into the form. Web wallet
  signs `register_with_sol_fee` with
  attestation_kind=WEB_DELEGATED and
  the hash from the phone.
```

The protocol-level guarantees match the Android-direct flow: the on-chain
program rejects any zero hash, and the leaf cert's attestation challenge
binds (phone, wallet) so a hash can't be replayed for a different identity.

## Run locally

```sh
pnpm install
pnpm dev
# open http://localhost:5173
```

The app talks to devnet via the public RPC defined in `src/lib/config.ts`.
Update that file (or the `RPC_URL` constant) if you redeploy elsewhere.

## Build

```sh
pnpm build
# outputs to dist/ — drop on any static host (Vercel, Netlify, GitHub Pages,
# Cloudflare Pages, IPFS via web3.storage, etc.)
```

## Deploy assumptions

- The deployed `phone_registry` program ID matches `PHONE_REGISTRY_PROGRAM`
  in `src/lib/config.ts`.
- The `RegistryConfig` PDA is initialized — the page reads `treasury`
  from it dynamically so a `set_treasury` rotation doesn't break the
  page.

## What this doesn't do (yet)

- **No SMS / no external services.** The phone is the source of truth
  for SIM ownership; the laptop just submits the tx.
- **No registration without a phone.** The laptop must hand off to a
  device that can read a SIM and run Android Keystore.
- **No Token-2022 SPL fee path.** SOL fee only for now. Add a second
  page or a tab if/when SPL-fee registration is wanted from web.

## Phone-side counterpart

The handoff terminates at `WebHandoffActivity` in the Android app:

- Manifest registers `overcall://web-register` as a deep-link target.
- Phone's native camera (or any QR scanner that resolves URIs) opens
  the deep link. The activity parses `phone`, `wallet`, `nonce`.
- Validates against the device's SIM (`SimReader` / libphonenumber).
- Generates a hardware-backed Keystore key with attestation challenge
  `sha256("overcall.attest" || phone || wallet.bytes)`.
- Renders `overcall://web-register-result?nonce=…&hash=<base58>` as a
  return QR. The laptop's camera reads it back into this page's hash
  field via `QrScanner.tsx`.
- The on-chain `attestation_kind` is set to `WEB_DELEGATED` (3) so
  off-chain verifiers can distinguish web-initiated registrations from
  in-app MWA registrations.
