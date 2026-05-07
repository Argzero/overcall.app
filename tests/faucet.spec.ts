import * as anchor from "@coral-xyz/anchor";
import { Program, BN } from "@coral-xyz/anchor";
import {
  Keypair,
  PublicKey,
  SystemProgram,
  LAMPORTS_PER_SOL,
} from "@solana/web3.js";
import {
  createMint,
  getAssociatedTokenAddressSync,
  getAccount,
  TOKEN_PROGRAM_ID,
  ASSOCIATED_TOKEN_PROGRAM_ID,
} from "@solana/spl-token";
import { expect } from "chai";
import { Faucet } from "../target/types/faucet";

describe("faucet", () => {
  const provider = anchor.AnchorProvider.env();
  anchor.setProvider(provider);

  const program = anchor.workspace.Faucet as Program<Faucet>;

  const [faucetConfigPda] = PublicKey.findProgramAddressSync(
    [Buffer.from("faucet_config")],
    program.programId
  );
  const [mintAuthorityPda] = PublicKey.findProgramAddressSync(
    [Buffer.from("mint_auth")],
    program.programId
  );

  const COOLDOWN_SECONDS = new BN(2); // short cooldown so cooldown-test can wait

  let testMint: PublicKey;
  const DRIP_AMOUNT = new BN(1_000_000); // 1 token at 6 decimals
  const DECIMALS = 6;

  it("init_faucet creates FaucetConfig with mint_authority bump", async () => {
    await program.methods
      .initFaucet(COOLDOWN_SECONDS)
      .accounts({
        faucetConfig: faucetConfigPda,
        mintAuthority: mintAuthorityPda,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();

    const cfg = await program.account.faucetConfig.fetch(faucetConfigPda);
    expect(cfg.admin.toBase58()).to.equal(provider.wallet.publicKey.toBase58());
    expect(cfg.cooldownSeconds.toString()).to.equal(COOLDOWN_SECONDS.toString());
    expect(cfg.mintAuthorityBump).to.be.greaterThan(0);
  });

  it("register_mint records a mint with drip_amount and decimals", async () => {
    // Create a mint whose authority is the faucet's mint_authority PDA.
    testMint = await createMint(
      provider.connection,
      (provider.wallet as anchor.Wallet).payer,
      mintAuthorityPda, // mint authority
      null, // freeze authority
      DECIMALS
    );

    const [mintInfoPda] = PublicKey.findProgramAddressSync(
      [Buffer.from("mint_info"), testMint.toBuffer()],
      program.programId
    );

    await program.methods
      .registerMint(DRIP_AMOUNT)
      .accounts({
        faucetConfig: faucetConfigPda,
        mint: testMint,
        mintInfo: mintInfoPda,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();

    const info = await program.account.mintInfo.fetch(mintInfoPda);
    expect(info.mint.toBase58()).to.equal(testMint.toBase58());
    expect(info.dripAmount.toString()).to.equal(DRIP_AMOUNT.toString());
    expect(info.decimals).to.equal(DECIMALS);
  });

  it("drip mints drip_amount to a fresh recipient ATA", async () => {
    const recipient = Keypair.generate();
    const sig = await provider.connection.requestAirdrop(
      recipient.publicKey,
      LAMPORTS_PER_SOL
    );
    await provider.connection.confirmTransaction(sig);

    const [mintInfoPda] = PublicKey.findProgramAddressSync(
      [Buffer.from("mint_info"), testMint.toBuffer()],
      program.programId
    );
    const [cooldownPda] = PublicKey.findProgramAddressSync(
      [
        Buffer.from("cooldown"),
        testMint.toBuffer(),
        recipient.publicKey.toBuffer(),
      ],
      program.programId
    );
    const recipientAta = getAssociatedTokenAddressSync(
      testMint,
      recipient.publicKey
    );

    await program.methods
      .drip()
      .accounts({
        faucetConfig: faucetConfigPda,
        mintInfo: mintInfoPda,
        mint: testMint,
        mintAuthority: mintAuthorityPda,
        recipientAta,
        cooldown: cooldownPda,
        recipient: recipient.publicKey,
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .signers([recipient])
      .rpc();

    const ata = await getAccount(provider.connection, recipientAta);
    expect(ata.amount.toString()).to.equal(DRIP_AMOUNT.toString());
  });

  it("rejects a second drip within the cooldown window", async () => {
    const recipient = Keypair.generate();
    const sig = await provider.connection.requestAirdrop(
      recipient.publicKey,
      LAMPORTS_PER_SOL
    );
    await provider.connection.confirmTransaction(sig);

    const [mintInfoPda] = PublicKey.findProgramAddressSync(
      [Buffer.from("mint_info"), testMint.toBuffer()],
      program.programId
    );
    const [cooldownPda] = PublicKey.findProgramAddressSync(
      [
        Buffer.from("cooldown"),
        testMint.toBuffer(),
        recipient.publicKey.toBuffer(),
      ],
      program.programId
    );
    const recipientAta = getAssociatedTokenAddressSync(
      testMint,
      recipient.publicKey
    );

    await program.methods
      .drip()
      .accounts({
        faucetConfig: faucetConfigPda,
        mintInfo: mintInfoPda,
        mint: testMint,
        mintAuthority: mintAuthorityPda,
        recipientAta,
        cooldown: cooldownPda,
        recipient: recipient.publicKey,
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .signers([recipient])
      .rpc();

    // Second drip immediately — should fail
    let threw = false;
    try {
      await program.methods
        .drip()
        .accounts({
          faucetConfig: faucetConfigPda,
          mintInfo: mintInfoPda,
          mint: testMint,
          mintAuthority: mintAuthorityPda,
          recipientAta,
          cooldown: cooldownPda,
          recipient: recipient.publicKey,
          tokenProgram: TOKEN_PROGRAM_ID,
          associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
          systemProgram: SystemProgram.programId,
        })
        .signers([recipient])
        .rpc();
    } catch (e) {
      threw = true;
    }
    expect(threw).to.equal(true, "expected cooldown violation");
  });

  it("permits a second drip after cooldown elapses", async () => {
    const recipient = Keypair.generate();
    const sig = await provider.connection.requestAirdrop(
      recipient.publicKey,
      LAMPORTS_PER_SOL
    );
    await provider.connection.confirmTransaction(sig);

    const [mintInfoPda] = PublicKey.findProgramAddressSync(
      [Buffer.from("mint_info"), testMint.toBuffer()],
      program.programId
    );
    const [cooldownPda] = PublicKey.findProgramAddressSync(
      [
        Buffer.from("cooldown"),
        testMint.toBuffer(),
        recipient.publicKey.toBuffer(),
      ],
      program.programId
    );
    const recipientAta = getAssociatedTokenAddressSync(
      testMint,
      recipient.publicKey
    );

    await program.methods
      .drip()
      .accounts({
        faucetConfig: faucetConfigPda,
        mintInfo: mintInfoPda,
        mint: testMint,
        mintAuthority: mintAuthorityPda,
        recipientAta,
        cooldown: cooldownPda,
        recipient: recipient.publicKey,
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .signers([recipient])
      .rpc();

    // Wait for cooldown to elapse (COOLDOWN_SECONDS = 2)
    await new Promise((r) => setTimeout(r, 3000));

    await program.methods
      .drip()
      .accounts({
        faucetConfig: faucetConfigPda,
        mintInfo: mintInfoPda,
        mint: testMint,
        mintAuthority: mintAuthorityPda,
        recipientAta,
        cooldown: cooldownPda,
        recipient: recipient.publicKey,
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .signers([recipient])
      .rpc();

    const ata = await getAccount(provider.connection, recipientAta);
    // Two drips landed
    expect(ata.amount.toString()).to.equal(
      DRIP_AMOUNT.muln(2).toString()
    );
  });

  it("update_mint_drip changes the per-mint drip amount", async () => {
    const [mintInfoPda] = PublicKey.findProgramAddressSync(
      [Buffer.from("mint_info"), testMint.toBuffer()],
      program.programId
    );
    const updated = new BN(7_777_777);
    await program.methods
      .updateMintDrip(updated)
      .accounts({
        faucetConfig: faucetConfigPda,
        mint: testMint,
        mintInfo: mintInfoPda,
        admin: provider.wallet.publicKey,
      })
      .rpc();
    const info = await program.account.mintInfo.fetch(mintInfoPda);
    expect(info.dripAmount.toString()).to.equal(updated.toString());

    // restore so subsequent tests are unaffected
    await program.methods
      .updateMintDrip(DRIP_AMOUNT)
      .accounts({
        faucetConfig: faucetConfigPda,
        mint: testMint,
        mintInfo: mintInfoPda,
        admin: provider.wallet.publicKey,
      })
      .rpc();
  });

  it("set_cooldown updates cooldown_seconds", async () => {
    const newCd = new BN(60);
    await program.methods
      .setCooldown(newCd)
      .accounts({ faucetConfig: faucetConfigPda, admin: provider.wallet.publicKey })
      .rpc();
    const cfg = await program.account.faucetConfig.fetch(faucetConfigPda);
    expect(cfg.cooldownSeconds.toString()).to.equal(newCd.toString());
    // restore
    await program.methods
      .setCooldown(COOLDOWN_SECONDS)
      .accounts({ faucetConfig: faucetConfigPda, admin: provider.wallet.publicKey })
      .rpc();
  });

  it("set_faucet_admin rotates admin and locks out the old one", async () => {
    const newAdmin = Keypair.generate();
    const sig = await provider.connection.requestAirdrop(
      newAdmin.publicKey,
      LAMPORTS_PER_SOL
    );
    await provider.connection.confirmTransaction(sig);

    await program.methods
      .setFaucetAdmin(newAdmin.publicKey)
      .accounts({ faucetConfig: faucetConfigPda, admin: provider.wallet.publicKey })
      .rpc();

    let threw = false;
    try {
      await program.methods
        .setCooldown(new BN(99))
        .accounts({ faucetConfig: faucetConfigPda, admin: provider.wallet.publicKey })
        .rpc();
    } catch (e) { threw = true; }
    expect(threw).to.equal(true, "old admin should be locked out");

    // restore admin
    await program.methods
      .setFaucetAdmin(provider.wallet.publicKey)
      .accounts({ faucetConfig: faucetConfigPda, admin: newAdmin.publicKey })
      .signers([newAdmin])
      .rpc();
  });

  it("rejects register_mint by non-admin", async () => {
    const intruder = Keypair.generate();
    const sig = await provider.connection.requestAirdrop(
      intruder.publicKey,
      LAMPORTS_PER_SOL
    );
    await provider.connection.confirmTransaction(sig);

    const fakeMint = await createMint(
      provider.connection,
      (provider.wallet as anchor.Wallet).payer,
      mintAuthorityPda,
      null,
      DECIMALS
    );
    const [mintInfoPda] = PublicKey.findProgramAddressSync(
      [Buffer.from("mint_info"), fakeMint.toBuffer()],
      program.programId
    );

    let threw = false;
    try {
      await program.methods
        .registerMint(DRIP_AMOUNT)
        .accounts({
          faucetConfig: faucetConfigPda,
          mint: fakeMint,
          mintInfo: mintInfoPda,
          admin: intruder.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([intruder])
        .rpc();
    } catch (e) {
      threw = true;
    }
    expect(threw).to.equal(true, "expected non-admin register_mint to fail");
  });
});
