/**
 * One-shot devnet bootstrap that runs after `anchor deploy`:
 *  1. init_config on phone_registry
 *  2. init_faucet  on faucet
 *  3. init_oracle on mock_swap
 *  4. Create dSKR / dUSDC / dWSOL test mints (mint_authority = faucet PDA)
 *  5. register_mint each with the faucet program
 *  6. Set fixture MockSwap prices for the relevant directed pairs
 *  7. Wire phone_registry.set_spl_fee_config to use dSKR as SPL fee mint
 *  8. Persist all addresses to config/devnet.json
 *
 * Usage:
 *   ANCHOR_WALLET=~/.config/solana/id.json \
 *   ANCHOR_PROVIDER_URL=https://api.devnet.solana.com \
 *   pnpm exec tsx scripts/deploy-bootstrap.ts
 */
import * as anchor from "@coral-xyz/anchor";
import { Program, BN } from "@coral-xyz/anchor";
import {
  Keypair,
  PublicKey,
  SystemProgram,
} from "@solana/web3.js";
import {
  createMint,
  setAuthority,
  AuthorityType,
  TOKEN_PROGRAM_ID,
  ASSOCIATED_TOKEN_PROGRAM_ID,
} from "@solana/spl-token";
import * as fs from "fs";
import * as path from "path";
import { fileURLToPath } from "url";

import { PhoneRegistry } from "../target/types/phone_registry";
import { Faucet } from "../target/types/faucet";
import { MockSwap } from "../target/types/mock_swap";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const CONFIG_PATH = path.join(__dirname, "..", "config", "devnet.json");

// Defaults (can override via env later)
const REGISTRATION_FEE_SOL_LAMPORTS = new BN(1_000_000); // 0.001 SOL
const PAYMENT_FEE_BPS = 1; // 0.01%
const FAUCET_COOLDOWN_SECONDS = new BN(3600); // 1 hour
const SPL_REGISTRATION_FEE_AMOUNT = new BN(100_000); // 0.1 dSKR (6 decimals)
const DRIP_AMOUNTS = {
  dSKR: new BN(100_000_000), // 100 dSKR per drip
  dUSDC: new BN(100_000_000), // 100 dUSDC per drip
  dWSOL: new BN(1_000_000_000), // 1 dWSOL per drip
};

async function main() {
  anchor.setProvider(anchor.AnchorProvider.env());
  const provider = anchor.getProvider() as anchor.AnchorProvider;
  const payer = (provider.wallet as anchor.Wallet).payer;

  const phoneRegistry = anchor.workspace.PhoneRegistry as Program<PhoneRegistry>;
  const faucet = anchor.workspace.Faucet as Program<Faucet>;
  const mockSwap = anchor.workspace.MockSwap as Program<MockSwap>;

  console.log("Cluster:", provider.connection.rpcEndpoint);
  console.log("Wallet :", provider.wallet.publicKey.toBase58());
  console.log();

  // --- 1. phone_registry init_config ----------------------------------
  const [registryConfig] = PublicKey.findProgramAddressSync(
    [Buffer.from("config")],
    phoneRegistry.programId
  );
  // For v1 the treasury is the deployer wallet. Rotate to a multisig later
  // via set_treasury.
  const treasury = provider.wallet.publicKey;

  const existingRegistryConfig = await phoneRegistry.account.registryConfig
    .fetchNullable(registryConfig);
  if (!existingRegistryConfig) {
    console.log("init_config (phone_registry) ...");
    await phoneRegistry.methods
      .initConfig(treasury, REGISTRATION_FEE_SOL_LAMPORTS, PAYMENT_FEE_BPS)
      .accounts({
        config: registryConfig,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();
    console.log("  ok:", registryConfig.toBase58());
  } else {
    console.log("init_config: already initialized");
  }

  // --- 2. faucet init_faucet ------------------------------------------
  const [faucetConfig] = PublicKey.findProgramAddressSync(
    [Buffer.from("faucet_config")],
    faucet.programId
  );
  const [faucetMintAuthority] = PublicKey.findProgramAddressSync(
    [Buffer.from("mint_auth")],
    faucet.programId
  );

  const existingFaucetConfig = await faucet.account.faucetConfig
    .fetchNullable(faucetConfig);
  if (!existingFaucetConfig) {
    console.log("init_faucet ...");
    await faucet.methods
      .initFaucet(FAUCET_COOLDOWN_SECONDS)
      .accounts({
        faucetConfig,
        mintAuthority: faucetMintAuthority,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();
    console.log("  ok:", faucetConfig.toBase58());
  } else {
    console.log("init_faucet: already initialized");
  }

  // --- 3. mock_swap init_oracle ---------------------------------------
  const [swapOracle] = PublicKey.findProgramAddressSync(
    [Buffer.from("oracle")],
    mockSwap.programId
  );

  const existingOracle = await mockSwap.account.oracleConfig
    .fetchNullable(swapOracle);
  if (!existingOracle) {
    console.log("init_oracle (mock_swap) ...");
    await mockSwap.methods
      .initOracle()
      .accounts({
        oracleConfig: swapOracle,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();
    console.log("  ok:", swapOracle.toBase58());
  } else {
    console.log("init_oracle: already initialized");
  }

  // --- 4. Create test mints under the faucet's mint authority ---------
  // We create each mint with `payer.publicKey` as the temporary mint
  // authority, then transfer the mint authority to faucet's PDA.
  const mints: Record<string, { pubkey: PublicKey; decimals: number; drip: BN }> = {};
  for (const [name, decimals, drip] of [
    ["dSKR", 6, DRIP_AMOUNTS.dSKR],
    ["dUSDC", 6, DRIP_AMOUNTS.dUSDC],
    ["dWSOL", 9, DRIP_AMOUNTS.dWSOL],
  ] as const) {
    console.log(`mint ${name} (${decimals} decimals) ...`);
    const mint = await createMint(
      provider.connection,
      payer,
      payer.publicKey, // temp authority
      null,
      decimals
    );
    await setAuthority(
      provider.connection,
      payer,
      mint,
      payer.publicKey,
      AuthorityType.MintTokens,
      faucetMintAuthority
    );
    mints[name] = { pubkey: mint, decimals, drip };
    console.log(`  ${name}:`, mint.toBase58());
  }

  // --- 5. faucet.register_mint for each mint --------------------------
  for (const [name, info] of Object.entries(mints)) {
    const [mintInfo] = PublicKey.findProgramAddressSync(
      [Buffer.from("mint_info"), info.pubkey.toBuffer()],
      faucet.programId
    );
    const existing = await faucet.account.mintInfo.fetchNullable(mintInfo);
    if (existing) {
      console.log(`register_mint ${name}: already registered`);
      continue;
    }
    console.log(`register_mint ${name} ...`);
    await faucet.methods
      .registerMint(info.drip)
      .accounts({
        faucetConfig,
        mint: info.pubkey,
        mintInfo,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();
  }

  // --- 6. mock_swap fixture prices ------------------------------------
  // Prices are unit-aware via decimals, but keep simple: fixture ratios
  // use raw on-chain integer math. We pick:
  //   dSKR  -> dUSDC : 1 dSKR  = 0.05 dUSDC      => num=5,  den=100
  //   dUSDC -> dSKR  : 1 dUSDC = 20 dSKR         => num=20, den=1
  //   dUSDC -> dWSOL : 1 dUSDC = 0.005 dWSOL*    => num=5,  den=1000  (* sub-decimal scaled later)
  //   dWSOL -> dUSDC : 1 dWSOL = 200 dUSDC       => num=200,den=1
  // (Real cross-decimal math is the client's job; these fixtures are
  // sufficient for end-to-end exercises on devnet.)
  const setPrice = async (
    inMint: PublicKey,
    outMint: PublicKey,
    num: number,
    den: number
  ) => {
    const [pricePda] = PublicKey.findProgramAddressSync(
      [Buffer.from("price"), inMint.toBuffer(), outMint.toBuffer()],
      mockSwap.programId
    );
    const existing = await mockSwap.account.price.fetchNullable(pricePda);
    if (existing) {
      console.log(
        `set_price ${inMint.toBase58().slice(0, 6)} -> ${outMint.toBase58().slice(0, 6)}: already set`
      );
      return;
    }
    await mockSwap.methods
      .setPrice(new BN(num), new BN(den))
      .accounts({
        oracleConfig: swapOracle,
        inMint,
        outMint,
        price: pricePda,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();
  };
  await setPrice(mints.dSKR.pubkey, mints.dUSDC.pubkey, 5, 100);
  await setPrice(mints.dUSDC.pubkey, mints.dSKR.pubkey, 20, 1);
  await setPrice(mints.dUSDC.pubkey, mints.dWSOL.pubkey, 5, 1000);
  await setPrice(mints.dWSOL.pubkey, mints.dUSDC.pubkey, 200, 1);
  console.log("MockSwap fixture prices set");

  // --- 7. phone_registry.set_spl_fee_config for dSKR ------------------
  console.log("set_spl_fee_config (dSKR as SPL registration fee) ...");
  await phoneRegistry.methods
    .setSplFeeConfig(mints.dSKR.pubkey, SPL_REGISTRATION_FEE_AMOUNT)
    .accounts({
      config: registryConfig,
      admin: provider.wallet.publicKey,
    })
    .rpc();

  // --- 8. Persist deployment ------------------------------------------
  const out = {
    cluster: provider.connection.rpcEndpoint,
    deployer: provider.wallet.publicKey.toBase58(),
    treasury: treasury.toBase58(),
    programs: {
      phone_registry: phoneRegistry.programId.toBase58(),
      faucet: faucet.programId.toBase58(),
      mock_swap: mockSwap.programId.toBase58(),
    },
    pdas: {
      registry_config: registryConfig.toBase58(),
      faucet_config: faucetConfig.toBase58(),
      faucet_mint_authority: faucetMintAuthority.toBase58(),
      swap_oracle: swapOracle.toBase58(),
    },
    mints: Object.fromEntries(
      Object.entries(mints).map(([k, v]) => [
        k,
        { mint: v.pubkey.toBase58(), decimals: v.decimals, drip_amount: v.drip.toString() },
      ])
    ),
    config: {
      registration_fee_sol_lamports: REGISTRATION_FEE_SOL_LAMPORTS.toString(),
      registration_fee_spl_amount: SPL_REGISTRATION_FEE_AMOUNT.toString(),
      payment_fee_bps: PAYMENT_FEE_BPS,
      faucet_cooldown_seconds: FAUCET_COOLDOWN_SECONDS.toString(),
    },
  };
  fs.mkdirSync(path.dirname(CONFIG_PATH), { recursive: true });
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(out, null, 2));
  console.log("\nWrote", CONFIG_PATH);
  console.log(JSON.stringify(out, null, 2));
}

main().then(
  () => process.exit(0),
  (err) => {
    console.error(err);
    process.exit(1);
  }
);
