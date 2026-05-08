# OverCall

In-call crypto payments for Solana Seeker. Detects active phone calls and overlays a swipe-dismissable bubble for sending crypto to the other party's wallet, resolved via a public on-chain phone-number registry.

Here's what it looks like in practice!




https://github.com/user-attachments/assets/27cb2719-b4f7-4404-912b-38db10f3d840


<img width="438" height="431" alt="Screenshot 2026-05-07 at 12 44 55 PM" src="https://github.com/user-attachments/assets/b59a4165-a911-403e-b42b-34a505e6ac39" />
<img width="436" height="1017" alt="Screenshot 2026-05-07 at 12 44 43 PM" src="https://github.com/user-attachments/assets/7d9daa97-5a68-441d-babc-58b68b159a75" />
<img width="468" height="1053" alt="Screenshot 2026-05-07 at 12 44 13 PM" src="https://github.com/user-attachments/assets/7fa149ed-8463-49cc-a843-e1ac2c6e875d" />
<img width="466" height="1021" alt="Screenshot 2026-05-07 at 12 43 45 PM" src="https://github.com/user-attachments/assets/3f2dd271-114c-4192-bd23-99e250893a48" />
<img width="410" height="947" alt="Screenshot 2026-05-07 at 11 28 46 AM" src="https://github.com/user-attachments/assets/bdbcd2bd-0740-4203-89e1-203a8fcfe2ed" />
<img width="410" height="940" alt="Screenshot 2026-05-07 at 11 28 27 AM" src="https://github.com/user-attachments/assets/01f4a2ca-f33e-4879-b7c1-15a629351dec" />

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
- **Galaxy A23:** sideloaded `dev` flavor, real telephony, MWA via Phantom (tested) or probably other wallets on devnet.
- **Seeker:** `seeker` flavor, MWA via bundled SVS wallet on devnet (Phantom fallback if SVS rejects devnet).
