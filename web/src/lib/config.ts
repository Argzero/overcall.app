import { PublicKey } from "@solana/web3.js";

/** Mirror of /config/devnet.json. Update when the on-chain deployment moves. */
export const RPC_URL = "https://api.devnet.solana.com";

export const PHONE_REGISTRY_PROGRAM = new PublicKey(
  "3NMgh3Urpb9opAeCWMGHtE1Tm5G4aFzTxQX4Wf4uqDNx",
);

export const REGISTRY_CONFIG_PDA = new PublicKey(
  "D1cUSzrZcRtitWsWP59dHXN4aMDEkxAvNrWPhTFbh48p",
);

/** Mirrors the on-chain `attestation_kind` u8 constants. */
export const ATTESTATION_KIND = {
  NONE: 0,
  ANDROID_KEYSTORE: 1,
  SEEKER_SEED_VAULT: 2,
  WEB_DELEGATED: 3,
} as const;
