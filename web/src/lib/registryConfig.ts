import { Connection, PublicKey } from "@solana/web3.js";
import { REGISTRY_CONFIG_PDA } from "./config";

export interface RegistryConfig {
  admin: PublicKey;
  treasury: PublicKey;
  registrationFeeSolLamports: bigint;
  registrationFeeSplAmount: bigint;
  feeSplMint: PublicKey;
  paymentFeeBps: number;
  registrationLifetime: bigint;
  paused: boolean;
}

/**
 * Borsh layout (post-Task-29):
 *   disc(8) bump(1) admin(32) treasury(32) regFeeSol(8) regFeeSpl(8)
 *   feeSplMint(32) paymentBps(2) regLifetime(8) paused(1) reserved(128)
 */
export async function fetchRegistryConfig(
  connection: Connection,
): Promise<RegistryConfig> {
  const info = await connection.getAccountInfo(REGISTRY_CONFIG_PDA);
  if (!info) throw new Error("RegistryConfig PDA not found on chain");
  const data = info.data;
  let off = 8 + 1; // skip discriminator + bump
  const admin = new PublicKey(data.subarray(off, off + 32)); off += 32;
  const treasury = new PublicKey(data.subarray(off, off + 32)); off += 32;
  const regFeeSol = data.readBigUInt64LE(off); off += 8;
  const regFeeSpl = data.readBigUInt64LE(off); off += 8;
  const feeSplMint = new PublicKey(data.subarray(off, off + 32)); off += 32;
  const paymentBps = data.readUInt16LE(off); off += 2;
  const regLifetime = data.readBigInt64LE(off); off += 8;
  const paused = data[off] !== 0;
  return {
    admin,
    treasury,
    registrationFeeSolLamports: regFeeSol,
    registrationFeeSplAmount: regFeeSpl,
    feeSplMint,
    paymentFeeBps: paymentBps,
    registrationLifetime: regLifetime,
    paused,
  };
}
