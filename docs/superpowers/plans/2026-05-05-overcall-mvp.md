# OverCall MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Solana Seeker–targeted Android app that detects active phone calls and overlays a swipe-dismissable bubble for sending crypto to the other party's wallet, resolved through a public on-chain phone-number registry. Run end-to-end on devnet from emulator and Galaxy S23, with a Seeker-validation path when a device arrives.

**Architecture:**
- Three Anchor programs on devnet: `phone_registry` (public phone↔address mapping with stake-based admission), `faucet` (mints test SPLs to dev wallets), `mock_swap` (Jupiter substitute on devnet).
- Single Android Kotlin app with two product flavors (`dev`, `seeker`); one foreground service handles call watching + overlay rendering. All signing through Mobile Wallet Adapter.
- **No backend services.** No Twilio, no Cloudflare Workers, no off-chain attestor. Registration is gated by an on-chain stake (SOL or SKR locked into the PhoneRecord PDA) plus the on-device SIM-read in the Android UI as a soft anchor. The on-chain program does not — and cannot — verify SIM ownership; squat resistance comes from stake economics.
- Test mints `dSKR`, `dUSDC`, `dWSOL` deployed under our control; default token sets reference these. Mainnet flip is a future-config decision, not on the build path.

**Tech Stack:**
- **On-chain:** Anchor v1.0.2 (Rust 1.93+), Solana CLI 3.1 (Anza), devnet
- **Android:** Kotlin 2.x, Jetpack Compose, AGP 8.x, target SDK 35, MWA clientlib-ktx 2.0.8, web3-solana, libphonenumber-android
- **Test wallet:** `solana-mobile/mock-mwa-wallet` (emulator), Backpack (S23), bundled SVS wallet (Seeker)

**Research-grounded decisions** (verified May 2026):
- Anchor v1.0.2 is current stable (released 2026-05-02). Use it.
- MWA Kotlin client uses `transact(sender) { authResult -> ... }` with `connect()` (not the older `authorize()` from 1.x).
- TEEPIN attestation APIs are NOT publicly documented for third-party dApps. **Decision:** registration is stake-gated, with the device's SIM-read E.164 as the default UI input. No external attestor on any surface.
- Solana devnet faucet limits: 1 SOL / 24h / wallet on most public faucets, 100 req/10s/IP on RPC. Use Helius or QuickNode RPC for the dev loop.
- Android 15: SYSTEM_ALERT_WINDOW alone no longer permits foreground-service start from background. Mitigation: one `OverCallForegroundService` with multi-type `phoneCall|specialUse`. The phone-call FGS exemption applies during active calls.
- Android `TelephonyManager.getLine1Number()` is the SIM E.164 read. Requires `READ_PHONE_NUMBERS` (deprecated `READ_SMS` no longer needed). May return null on dual-SIM ambiguity, eSIM-without-MSISDN, or carriers that suppress the field — UI must handle that and let the user proceed with manual entry (with a warning).

---

## File Structure

```
over/
├── Anchor.toml                                # workspace config: programs, scripts, cluster=devnet
├── Cargo.toml                                 # workspace
├── package.json                               # TS test deps (mocha, ts-node, @solana/web3.js, @coral-xyz/anchor)
├── tsconfig.json
├── .gitignore
├── README.md
├── programs/
│   ├── phone_registry/
│   │   ├── Cargo.toml
│   │   ├── Xargo.toml
│   │   └── src/
│   │       ├── lib.rs                         # entrypoints, declare_id
│   │       ├── error.rs                       # ErrorCode enum
│   │       ├── state.rs                       # PhoneRecord, ReverseIndex, RegistryConfig accounts
│   │       └── instructions/
│   │           ├── mod.rs
│   │           ├── init_config.rs             # one-time admin: create RegistryConfig
│   │           ├── register.rs                # create PhoneRecord + ReverseIndex (attestation-gated)
│   │           ├── update_preferences.rs      # owner-only edit of mints/flags
│   │           ├── rotate_owner.rs            # transfer to new owner with fresh attestation
│   │           └── revoke.rs                  # close PDAs, refund rent
│   ├── faucet/
│   │   ├── Cargo.toml
│   │   └── src/lib.rs                         # init_mint, drip with per-recipient rate-limit
│   └── mock_swap/
│       ├── Cargo.toml
│       └── src/lib.rs                         # set_price, swap_and_send (fixture oracle)
├── tests/
│   ├── phone_registry.spec.ts                 # all instructions + duplicate rejection + stake refund
│   ├── faucet.spec.ts                         # mint, drip, rate-limit
│   └── mock_swap.spec.ts                      # price set, swap, slippage rejection
├── android/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── app/
│   │   ├── build.gradle.kts
│   │   ├── proguard-rules.pro
│   │   └── src/
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml
│   │       │   ├── kotlin/com/overcall/
│   │       │   │   ├── OverCallApp.kt
│   │       │   │   ├── BuildConfigExt.kt
│   │       │   │   ├── di/AppModule.kt
│   │       │   │   ├── device/DeviceProfile.kt
│   │       │   │   ├── registry/PhoneNumber.kt
│   │       │   │   ├── registry/PhoneRecord.kt
│   │       │   │   ├── registry/RegistryClient.kt
│   │       │   │   ├── pay/TestMints.kt
│   │       │   │   ├── pay/RouteBuilder.kt
│   │       │   │   ├── pay/PaymentService.kt
│   │       │   │   ├── pay/MwaSigner.kt
│   │       │   │   ├── pay/WalletWatcher.kt   # RPC ws subscription, emits PaymentReceived
│   │       │   │   ├── call/CallWatcher.kt
│   │       │   │   ├── call/SimReader.kt      # TelephonyManager.getLine1Number wrapper
│   │       │   │   ├── call/OutgoingCallReceiver.kt
│   │       │   │   ├── call/PhoneNumberCapture.kt
│   │       │   │   ├── overlay/OverlayController.kt
│   │       │   │   ├── overlay/BubbleState.kt # state machine: Sender / Recipient / Idle
│   │       │   │   ├── overlay/BubbleView.kt
│   │       │   │   ├── overlay/PanelView.kt
│   │       │   │   ├── service/OverCallForegroundService.kt
│   │       │   │   └── ui/{HomeScreen,RegisterScreen,SendScreen,HistoryScreen}.kt
│   │       │   └── res/
│   │       ├── dev/AndroidManifest.xml        # NO seeker feature gate
│   │       ├── seeker/AndroidManifest.xml     # required="true" feature gate
│   │       └── test/kotlin/com/overcall/      # JVM unit tests
├── docs/
│   └── superpowers/plans/2026-05-05-overcall-mvp.md   # this file
└── scripts/
    ├── deploy-devnet.sh
    ├── seed-test-mints.sh
    └── emu-call.sh                            # adb emu gsm helpers
```

---

## High-Level Task List (granular sub-steps below for Task 1; subsequent tasks expanded just-in-time before execution)

| # | Task | Subsystem | Done-when |
|---|---|---|---|
| 1 | Init repo + Anchor workspace | infra | `anchor build` succeeds, git initialized, CI-ready scaffolding ✅ |
| 2 | `phone_registry` — accounts + init_config | on-chain | localnet test creates `RegistryConfig` (admin + min stake) |
| 3 | `phone_registry` — register (no attestation) | on-chain | localnet test registers a phone, rejects duplicate, locks stake |
| 4 | `phone_registry` — update_preferences, rotate_owner, revoke | on-chain | full instruction set localnet-tested; revoke refunds stake |
| 5 | `faucet` program | on-chain | mints dSKR/dUSDC/dWSOL, drip rate-limit verified |
| 6 | `mock_swap` program | on-chain | set_price + swap_and_send + slippage rejection tested |
| 7 | Devnet deploy of all three programs + config init | infra | program IDs in `Anchor.toml`, three test mints created |
| 11 | Android scaffold — flavors + DeviceProfile + manifest | android | `dev` and `seeker` flavors build; profile detect runs on emulator |
| 12 | Android RegistryClient — forward/reverse lookup against devnet | android | unit test resolves a registered E.164 to a PhoneRecord |
| 13 | Android MWA wiring — connect + sign on devnet via mock-mwa-wallet | android | emulator + mock wallet returns a signed tx |
| 14 | Android Registration UI — SIM read → on-chain register | android | E2E: emulator registers SIM-read number on devnet |
| 15 | Android Pay flow — direct SPL transfer | android | E2E: dSKR transfer to a registered E.164 |
| 16 | Android Pay flow — MockSwap-routed transfer | android | E2E: dUSDC→dSKR swap-and-send on devnet |
| 17 | Foreground service + call watcher | android | `adb emu gsm call` triggers service start |
| 18 | Overlay bubble + panel + PiP gestures | android | bubble draws, drags, swipe-dismisses, in-app reopen |
| 19 | Bubble sender flow — resolve → MWA send | android | call → bubble → tap → send dSKR end-to-end on emulator |
| 23 | Bubble recipient flow — RPC websocket → received card | android | bubble updates with "Received $X from +1…" within ~3s of incoming transfer during a call |
| 20 | Bubble — unregistered-recipient state + share-invite link | android | bubble shows "+1 (555)… not on OverCall" + share QR/link |
| 21 | Galaxy S23 sideload + real-call validation | validation | dev APK on S23 detects calls, draws overlay, sends via Backpack on devnet |
| 22 | Seeker validation (when device available) | validation | seeker flavor signs through SVS wallet on devnet |

Tasks 8–10 (the attestor service) were dropped — registration is unattested in v1, anti-squatting deferred. Tasks 1–7 are the on-chain critical path. Tasks 11–18 build the app foundations. Tasks 19+23 are the **paired MVP done-when** (sender + recipient bubble feedback). Tasks 20–22 close out invite UX and hardware validation.

---

## Task 1: Init repo + Anchor workspace

**Files:**
- Create: `.gitignore`
- Create: `Anchor.toml`
- Create: `Cargo.toml` (workspace)
- Create: `package.json`, `tsconfig.json`
- Create: `README.md`
- Create: `programs/phone_registry/Cargo.toml`
- Create: `programs/phone_registry/Xargo.toml`
- Create: `programs/phone_registry/src/lib.rs` (stub: `declare_id!` + empty `#[program] mod`)

- [ ] **Step 1.1: Verify anchor toolchain available**

```bash
avm --version || cargo install --git https://github.com/solana-foundation/anchor avm --locked
avm install 1.0.2
avm use 1.0.2
anchor --version
```

Expected: `anchor-cli 1.0.2`

- [ ] **Step 1.2: Initialize git in working dir**

```bash
cd /Users/fzshooster/Desktop/personal/blockchain/solana/over
git init -b main
```

Expected: empty repo on `main`

- [ ] **Step 1.3: Write `.gitignore`**

```gitignore
# Rust
target/
**/target/
Cargo.lock

# Anchor
.anchor/
test-ledger/

# Node
node_modules/
*.log
.wrangler/
dist/
.env
.env.local

# Android
android/app/build/
android/build/
android/.gradle/
android/local.properties
*.apk
*.keystore

# IDE
.idea/
.vscode/
*.iml
.DS_Store
```

- [ ] **Step 1.4: Write `Anchor.toml`**

```toml
[toolchain]
anchor_version = "1.0.2"

[features]
resolution = true
skip-lint = false

[programs.localnet]
phone_registry = "PhRegnLnEt11111111111111111111111111111111"
faucet         = "FaUcetnLnEt11111111111111111111111111111111"
mock_swap      = "MoCkSwAplnEt1111111111111111111111111111111"

[programs.devnet]
phone_registry = "PhRegDevnEt11111111111111111111111111111111"
faucet         = "FaUcetDevnEt11111111111111111111111111111111"
mock_swap      = "MoCkSwApDevnEt111111111111111111111111111111"

[registry]
url = "https://api.apr.dev"

[provider]
cluster = "devnet"
wallet  = "~/.config/solana/id.json"

[scripts]
test = "yarn run ts-mocha -p ./tsconfig.json -t 1000000 tests/**/*.spec.ts"
```

(Program IDs above are placeholders; Step 1.10 replaces them with real keypair-derived IDs.)

- [ ] **Step 1.5: Write workspace `Cargo.toml`**

```toml
[workspace]
members = ["programs/*"]
resolver = "2"

[profile.release]
overflow-checks = true
lto = "fat"
codegen-units = 1

[profile.release.build-override]
opt-level = 3
incremental = false
codegen-units = 1
```

- [ ] **Step 1.6: Write `programs/phone_registry/Cargo.toml`**

```toml
[package]
name    = "phone_registry"
version = "0.1.0"
edition = "2021"
description = "OverCall public on-chain phone-number registry"

[lib]
crate-type = ["cdylib", "lib"]
name = "phone_registry"

[features]
no-entrypoint = []
no-idl = []
no-log-ix-name = []
cpi = ["no-entrypoint"]
default = []
idl-build = ["anchor-lang/idl-build"]

[dependencies]
anchor-lang = { version = "0.31.1", features = ["init-if-needed"] }
```

(Anchor v1.0.2's runtime crate is published as `anchor-lang` 0.31.x for backwards compatibility with the 0.x ecosystem; v1 is mostly CLI changes. If `anchor build` reports a version mismatch, switch to `anchor-lang = "1.0.2"`.)

- [ ] **Step 1.7: Write `programs/phone_registry/src/lib.rs` stub**

```rust
use anchor_lang::prelude::*;

declare_id!("PhRegnLnEt11111111111111111111111111111111");

#[program]
pub mod phone_registry {
    use super::*;
}
```

- [ ] **Step 1.8: Write `programs/phone_registry/Xargo.toml`**

```toml
[target.bpfel-unknown-unknown.dependencies.std]
features = []
```

- [ ] **Step 1.9: Write `package.json`**

```json
{
  "name": "overcall",
  "version": "0.1.0",
  "private": true,
  "scripts": {
    "test": "ts-mocha -p ./tsconfig.json -t 1000000 tests/**/*.spec.ts",
    "lint": "eslint . --ext .ts"
  },
  "devDependencies": {
    "@coral-xyz/anchor": "^0.31.1",
    "@solana/web3.js": "^1.95.0",
    "@solana/spl-token": "^0.4.6",
    "@types/bn.js": "^5.1.5",
    "@types/chai": "^4.3.16",
    "@types/mocha": "^10.0.6",
    "@types/node": "^20.14.0",
    "chai": "^4.4.1",
    "mocha": "^10.4.0",
    "ts-mocha": "^10.0.0",
    "ts-node": "^10.9.2",
    "tweetnacl": "^1.0.3",
    "typescript": "^5.4.5"
  }
}
```

- [ ] **Step 1.10: Write `tsconfig.json`**

```json
{
  "compilerOptions": {
    "types": ["mocha", "chai", "node"],
    "typeRoots": ["./node_modules/@types"],
    "lib": ["es2020"],
    "module": "commonjs",
    "target": "es2020",
    "esModuleInterop": true,
    "skipLibCheck": true,
    "strict": true,
    "resolveJsonModule": true
  }
}
```

- [ ] **Step 1.11: Generate program keypairs and update IDs**

```bash
mkdir -p target/deploy
solana-keygen new --no-bip39-passphrase --silent --outfile target/deploy/phone_registry-keypair.json
solana-keygen new --no-bip39-passphrase --silent --outfile target/deploy/faucet-keypair.json
solana-keygen new --no-bip39-passphrase --silent --outfile target/deploy/mock_swap-keypair.json

PR_ID=$(solana address -k target/deploy/phone_registry-keypair.json)
FA_ID=$(solana address -k target/deploy/faucet-keypair.json)
MS_ID=$(solana address -k target/deploy/mock_swap-keypair.json)

echo "phone_registry: $PR_ID"
echo "faucet:         $FA_ID"
echo "mock_swap:      $MS_ID"
```

Then update the `declare_id!` line in `programs/phone_registry/src/lib.rs` and the program IDs in `Anchor.toml` to match.

- [ ] **Step 1.12: Write `README.md`**

```markdown
# OverCall

In-call crypto payments for Solana Seeker. Detects active phone calls and overlays a swipe-dismissable bubble for sending crypto to the other party's wallet, resolved via a public on-chain phone-number registry.

**Status:** devnet only. See `docs/superpowers/plans/` for implementation plans.

## Layout

- `programs/` — Anchor programs: `phone_registry`, `faucet`, `mock_swap`
- `tests/` — TS integration tests against localnet
- `attestor/` — Cloudflare Worker: SMS-OTP attestor + invite delivery
- `android/` — Kotlin/Compose app, two flavors (`dev`, `seeker`)

## Build

    yarn install
    anchor build
    anchor test
```

- [ ] **Step 1.13: Run `anchor build` to confirm scaffolding compiles**

```bash
anchor build
```

Expected: `Finished release [optimized] target(s)` with `target/deploy/phone_registry.so` produced.

- [ ] **Step 1.14: Initial commit**

```bash
git add .gitignore Anchor.toml Cargo.toml package.json tsconfig.json README.md \
        programs/phone_registry docs/
git commit -m "chore: scaffold Anchor workspace and phone_registry stub"
```

---

## Task 2: `phone_registry` accounts + `init_config`

(Detailed expansion deferred — will be written immediately before Task 2 execution. Outline:)

**Files to create:** `programs/phone_registry/src/state.rs`, `programs/phone_registry/src/error.rs`, `programs/phone_registry/src/instructions/{mod.rs,init_config.rs}`, `tests/phone_registry.spec.ts`.

**Account shape (from design):**
```rust
#[account]
pub struct RegistryConfig {
    pub bump: u8,
    pub admin: Pubkey,
    pub attestor_count: u8,
    pub attestors: [Pubkey; 8],
    pub attestor_kinds: [u8; 8],          // 0=TEEPIN, 1=MOCK_DEV, 2=WEB_SMS_OTP
    pub min_attestation_age: i64,
    pub max_attestation_lifetime: i64,
}

#[account]
pub struct PhoneRecord {
    pub bump: u8,
    pub phone_e164: [u8; 16],
    pub phone_len: u8,
    pub owner: Pubkey,
    pub accepted_mints: [Pubkey; 8],
    pub accepted_count: u8,
    pub preferred_receive: Pubkey,
    pub attestor: Pubkey,
    pub attestation_expiry: i64,
    pub attestation_nonce: [u8; 16],      // for replay prevention
    pub registered_at: i64,
    pub flags: u32,
}

#[account]
pub struct ReverseIndex {
    pub bump: u8,
    pub owner: Pubkey,
    pub phone_record: Pubkey,
}
```

**Test:** init_config with 3-attestor set, assert all fields, assert only `admin` can call.

---

## Tasks 3–23

Each will be expanded with full TDD step detail just before execution. Their goals are listed in the High-Level Task List above. Subsequent tasks share the same shape:

1. Write failing test(s).
2. Run them to confirm they fail.
3. Implement minimum to pass.
4. Run, confirm pass.
5. Commit.

The MVP definition-of-done is Tasks 19 + 23 together:
- **19 (sender):** Emulator A places a call to a registered devnet number → bubble shows "Send to +1…" → user taps "Send 1 dSKR" → mock-mwa-wallet signs → SPL transfer confirms → bubble shows tx confirmation.
- **23 (recipient):** Emulator B is on a call when emulator A's transfer hits B's wallet → B's bubble updates to "Received 1 dSKR from +1 (555) 123-4567" within ~3s, sender phone reverse-looked-up via ReverseIndex, libphonenumber-formatted per locale.
