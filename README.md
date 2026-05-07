# OverCall

In-call crypto payments for Solana Seeker. Detects active phone calls and overlays a swipe-dismissable bubble for sending crypto to the other party's wallet, resolved via a public on-chain phone-number registry.

NOTE: fixing bug with github creds atm...

**Status:** devnet only. See `docs/superpowers/plans/` for implementation plans.

## Layout

- `programs/` — Anchor programs: `phone_registry`, `faucet`, `mock_swap`
- `tests/` — TS integration tests against localnet/devnet
- `attestor/` — Cloudflare Worker: SMS-OTP attestor + invite delivery
- `android/` — Kotlin/Compose app, two flavors (`dev`, `seeker`)

## Build

```sh
yarn install
anchor build
anchor test
```

## Test surfaces

- **Emulator:** Android AVD with `solana-mobile/mock-mwa-wallet` for MWA signing on devnet.
- **Galaxy S23:** sideloaded `dev` flavor, real telephony, MWA via Backpack on devnet.
- **Seeker:** `seeker` flavor, MWA via bundled SVS wallet on devnet (Backpack fallback if SVS rejects devnet).
