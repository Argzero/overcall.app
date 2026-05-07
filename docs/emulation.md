# Emulator-based end-to-end testing

This is the runbook we used to drive the Register Phone → on-chain
`register_with_sol_fee` flow without a physical Seeker / S23. It loops
the laptop, an Android Virtual Device, and Solana Mobile's reference
`fakewallet` MWA wallet through the same code path as the real device,
and lands a finalized devnet transaction.

## One-time setup

```bash
# Install platform-tools / emulator / a system image, create the AVD.
./scripts/setup-android-emu.sh

# Clone + build + install fakewallet on the AVD. Idempotent.
./scripts/install-fakewallet.sh
```

`setup-android-emu.sh` pulls `system-images;android-35;google_apis;<arch>`
(arm64-v8a on Apple Silicon, x86_64 elsewhere). On macOS Homebrew's
`sdkmanager` (v20) can't see modern system images, so the script
PATH-prefixes Google's `cmdline-tools/latest`.

`install-fakewallet.sh` clones `solana-mobile/mobile-wallet-adapter`
into `tools/mwa/`, runs its `:fakewallet:assembleV1Debug` task, and
adb-installs the resulting APK. It also installs Seed Vault Simulator
from `solana-mobile/seed-vault-sdk` if you ran `install-seed-vault.sh`.

Optional but recommended:

```bash
adb -e logcat -G 16M   # bigger logcat ring buffer; default rolls fast
```

## Running the loop

```bash
# Source env (sets JAVA_HOME, ANDROID_HOME, PATH, OVERCALL_AVD,
# and pins ANDROID_SERIAL=emulator-5554 if both an AVD and a physical
# device are attached).
source scripts/dev-env.sh

./scripts/start-emu.sh         # boots the AVD headed
./scripts/install-app.sh       # ./gradlew :app:installDevDebug + verifies deep-link intent
```

Then drive the UI in the AVD. Expected happy-path flow:

1. **Tap Connect Wallet.** fakewallet's "Authorize Dapp" screen pops.
   Tap **AUTHORIZE**. fakewallet generates a fresh keypair every time
   it's force-stopped (no on-disk persistence by default), so the
   pubkey shown in OverCall is volatile across reinstalls.
2. **Fund the connected pubkey.** Devnet airdrop is heavily
   rate-limited. Easier:
   ```bash
   solana transfer <pubkey-from-OverCall-home> 0.05 \
       --url devnet --allow-unfunded-recipient
   ```
   Use a **reserved-fictional-but-valid** US number for the phone
   field, e.g. `+14155550100`. The 555-1234 numbers are *fictional* —
   `libphonenumber.isValidNumber` rejects them and OverCall surfaces
   "Phone number isn't valid E.164". Reserved range 555-0100..0199
   passes validation.
3. **Tap Register Phone, enter `+14155550100`, tap Register.**
   fakewallet's "Sign Transaction(s)" screen appears.
4. **Tap AUTHORIZE.** fakewallet then shows "Sending Transaction(s)"
   with a **SEND TRANSACTION TO CLUSTER** button.
5. **Tap SEND TRANSACTION TO CLUSTER.** fakewallet broadcasts to
   `https://api.devnet.solana.com` and returns the signature to
   OverCall. The home screen shows "Last tx: …" with the base58
   signature prefix.

## Verifying on-chain

```bash
solana confirm -v <signature> --url devnet
```

Expected: `Status: Ok` and `Program 3NMgh3Urpb9... success` ending in
`Finalized`. Treasury (`6SWskncXVVNQ4ubLFnMCS3jwd7BTJ3sy5BRaFoFJRNKR`)
balance should rise by the registration fee (0.001 SOL by default).

You can also dump the resulting accounts:

```bash
# phoneRecord PDA — should be 367 bytes, owned by phone_registry
solana account <phoneRecordPda> --url devnet

# reverseIndex PDA — 73 bytes, owned by phone_registry
solana account <reverseIndexPda> --url devnet
```

Both PDAs are visible in the tx hex (see "Reading the wire format"
below) at account positions 1 and 2 respectively.

## Web → phone QR-handoff smoke test

The deep-link path is normally triggered by scanning the laptop's QR
with the AVD's camera, which is awkward in an emulator. Skip the
camera and fire the intent directly:

```bash
./scripts/deeplink-web-register.sh '+14155550100' <wallet-base58> <nonce>
```

Then on the device, tap "Generate attestation". To extract the result:

```bash
./scripts/grab-attestation-hash.sh
```

Tails logcat for the `OverCall/WebHandoff` tag and prints the
`hash=` query param from the return URI.

## Known emulator-specific quirks

- **MWA session timeout**: the JSON-RPC client's default per-method
  timeout is 90s. UI-driving via `adb shell input` is slow enough to
  blow this when you also need to take screenshots between taps.
  `MwaSigner.SESSION_TIMEOUT_MS` is bumped to 5 minutes for emulator
  comfort; production users tap within seconds, so this is harmless.
- **fakewallet keypair rotation**: every `force-stop` of fakewallet
  loses its in-memory keypair. After each cold start you'll have a new
  pubkey; refund it via `solana transfer` before tapping Register.
- **Two-device adb**: when both an emulator and a physical device
  (S23 / Seeker) are plugged in, ambiguous `adb` and `gradle install`
  commands fail. `dev-env.sh` exports `ANDROID_SERIAL=emulator-5554`
  if it sees a running emulator, and every helper script uses
  `adb -e` explicitly.
- **App Links verification warning**: fakewallet logs
  `ClientTrustUseCase: Package verification failed for callingPackage=com.overcall.dev`
  because we don't host `https://overcall.app/.well-known/assetlinks.json`.
  fakewallet still authorizes (downgrades scope to `app`); this won't
  apply once the app is published with an associated domain.
- **Devnet faucet rate limits**: `solana airdrop` is heavily throttled
  on devnet. The transfer-from-funded-keypair pattern above bypasses
  the faucet entirely.

## Reading the wire format (debugging)

When `BuildConfig.DEBUG` is set, `MwaSigner.signAndSend` hex-dumps
each transaction at `Log.d` under the `OverCall/MwaSigner` tag before
handing it to MWA. To inspect:

```bash
adb -e logcat -d | grep "OverCall/MwaSigner.*tx\["
```

Bytes are formatted per the Solana wire protocol:

```
[compact-u16 num_signatures]            # always 1 for our register tx
[64 zero bytes per signature placeholder]
[1 byte numRequiredSignatures (matches)]
[1 byte numReadOnlySigned]
[1 byte numReadOnlyUnsigned]
[compact-u16 num_accounts]
[32 bytes × num_accounts]              # signers first, fee payer at index 0
[32 bytes recentBlockhash]
[compact-u16 num_instructions]
[per-instruction: programIdIdx, accountIdxs, data]
```

Account at index 0 must equal the wallet's connected pubkey, otherwise
fakewallet rejects with `InvalidPayloadsException: payloads invalid for signing`.
