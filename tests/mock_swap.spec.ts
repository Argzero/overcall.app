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
  getOrCreateAssociatedTokenAccount,
  getAssociatedTokenAddressSync,
  getAccount,
  mintTo,
  TOKEN_PROGRAM_ID,
  ASSOCIATED_TOKEN_PROGRAM_ID,
} from "@solana/spl-token";
import { expect } from "chai";
import { MockSwap } from "../target/types/mock_swap";

describe("mock_swap", () => {
  const provider = anchor.AnchorProvider.env();
  anchor.setProvider(provider);

  const program = anchor.workspace.MockSwap as Program<MockSwap>;
  const payer = (provider.wallet as anchor.Wallet).payer;

  const [oraclePda] = PublicKey.findProgramAddressSync(
    [Buffer.from("oracle")],
    program.programId
  );

  let mintA: PublicKey; // input
  let mintB: PublicKey; // output
  let pricePda: PublicKey;
  let reserveAuthorityB: PublicKey;
  let reserveAtaB: PublicKey;

  before(async () => {
    mintA = await createMint(provider.connection, payer, payer.publicKey, null, 6);
    mintB = await createMint(provider.connection, payer, payer.publicKey, null, 6);

    [pricePda] = PublicKey.findProgramAddressSync(
      [Buffer.from("price"), mintA.toBuffer(), mintB.toBuffer()],
      program.programId
    );
    [reserveAuthorityB] = PublicKey.findProgramAddressSync(
      [Buffer.from("reserve_auth"), mintB.toBuffer()],
      program.programId
    );
    reserveAtaB = getAssociatedTokenAddressSync(mintB, reserveAuthorityB, true);
  });

  it("init_oracle creates the OracleConfig", async () => {
    await program.methods
      .initOracle()
      .accounts({
        oracleConfig: oraclePda,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();

    const cfg = await program.account.oracleConfig.fetch(oraclePda);
    expect(cfg.admin.toBase58()).to.equal(provider.wallet.publicKey.toBase58());
  });

  it("set_price stores numerator and denominator for a directed pair", async () => {
    // 1 mintA -> 2 mintB
    await program.methods
      .setPrice(new BN(2), new BN(1))
      .accounts({
        oracleConfig: oraclePda,
        inMint: mintA,
        outMint: mintB,
        price: pricePda,
        admin: provider.wallet.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();

    const p = await program.account.price.fetch(pricePda);
    expect(p.inMint.toBase58()).to.equal(mintA.toBase58());
    expect(p.outMint.toBase58()).to.equal(mintB.toBase58());
    expect(p.numerator.toString()).to.equal("2");
    expect(p.denominator.toString()).to.equal("1");
  });

  it("rejects set_price by non-admin", async () => {
    const intruder = Keypair.generate();
    const sig = await provider.connection.requestAirdrop(
      intruder.publicKey,
      LAMPORTS_PER_SOL
    );
    await provider.connection.confirmTransaction(sig);

    let threw = false;
    try {
      await program.methods
        .setPrice(new BN(99), new BN(1))
        .accounts({
          oracleConfig: oraclePda,
          inMint: mintA,
          outMint: mintB,
          price: pricePda,
          admin: intruder.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([intruder])
        .rpc();
    } catch (e) {
      threw = true;
    }
    expect(threw).to.equal(true, "expected non-admin to be rejected");
  });

  it("fund_reserve transfers tokens into the output reserve ATA", async () => {
    // Provider mints 1_000_000 mintB to themselves, then funds reserve.
    const funderAtaB = await getOrCreateAssociatedTokenAccount(
      provider.connection,
      payer,
      mintB,
      payer.publicKey
    );
    await mintTo(
      provider.connection,
      payer,
      mintB,
      funderAtaB.address,
      payer.publicKey,
      1_000_000
    );

    await program.methods
      .fundReserve(new BN(500_000))
      .accounts({
        mint: mintB,
        reserveAuthority: reserveAuthorityB,
        reserveAta: reserveAtaB,
        funderAta: funderAtaB.address,
        funder: payer.publicKey,
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .rpc();

    const reserve = await getAccount(provider.connection, reserveAtaB);
    expect(reserve.amount.toString()).to.equal("500000");
  });

  it("swap_and_send delivers output to recipient at the configured price", async () => {
    // Sender holds 1000 mintA. recipient is a fresh wallet.
    const sender = Keypair.generate();
    const recipient = Keypair.generate();
    for (const kp of [sender, recipient]) {
      const sig = await provider.connection.requestAirdrop(
        kp.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);
    }

    const senderAtaA = await getOrCreateAssociatedTokenAccount(
      provider.connection,
      payer,
      mintA,
      sender.publicKey
    );
    await mintTo(
      provider.connection,
      payer,
      mintA,
      senderAtaA.address,
      payer.publicKey,
      1_000
    );

    const recipientAtaB = getAssociatedTokenAddressSync(mintB, recipient.publicKey);

    await program.methods
      .swapAndSend(new BN(100), new BN(200))
      .accounts({
        price: pricePda,
        inMint: mintA,
        outMint: mintB,
        inReserveAuthority: PublicKey.findProgramAddressSync(
          [Buffer.from("reserve_auth"), mintA.toBuffer()],
          program.programId
        )[0],
        outReserveAuthority: reserveAuthorityB,
        inReserveAta: getAssociatedTokenAddressSync(
          mintA,
          PublicKey.findProgramAddressSync(
            [Buffer.from("reserve_auth"), mintA.toBuffer()],
            program.programId
          )[0],
          true
        ),
        outReserveAta: reserveAtaB,
        senderInAta: senderAtaA.address,
        recipientOutAta: recipientAtaB,
        recipient: recipient.publicKey,
        sender: sender.publicKey,
        tokenProgram: TOKEN_PROGRAM_ID,
        associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
        systemProgram: SystemProgram.programId,
      })
      .signers([sender])
      .rpc();

    const out = await getAccount(provider.connection, recipientAtaB);
    expect(out.amount.toString()).to.equal("200"); // 100 * 2 / 1 = 200
  });

  it("set_swap_admin rotates and locks out the old admin", async () => {
    const newAdmin = Keypair.generate();
    const sig = await provider.connection.requestAirdrop(
      newAdmin.publicKey,
      LAMPORTS_PER_SOL
    );
    await provider.connection.confirmTransaction(sig);

    await program.methods
      .setSwapAdmin(newAdmin.publicKey)
      .accounts({ oracleConfig: oraclePda, admin: provider.wallet.publicKey })
      .rpc();

    // Old admin can no longer set_price.
    const fresh = await createMint(provider.connection, payer, payer.publicKey, null, 6);
    const [pricePda2] = PublicKey.findProgramAddressSync(
      [Buffer.from("price"), mintA.toBuffer(), fresh.toBuffer()],
      program.programId
    );
    let threw = false;
    try {
      await program.methods
        .setPrice(new BN(7), new BN(3))
        .accounts({
          oracleConfig: oraclePda,
          inMint: mintA,
          outMint: fresh,
          price: pricePda2,
          admin: provider.wallet.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .rpc();
    } catch (e) { threw = true; }
    expect(threw).to.equal(true, "old admin should be locked out");

    // restore admin
    await program.methods
      .setSwapAdmin(provider.wallet.publicKey)
      .accounts({ oracleConfig: oraclePda, admin: newAdmin.publicKey })
      .signers([newAdmin])
      .rpc();
  });

  it("rejects swap when min_output exceeds computed output (slippage)", async () => {
    const sender = Keypair.generate();
    const recipient = Keypair.generate();
    for (const kp of [sender, recipient]) {
      const sig = await provider.connection.requestAirdrop(
        kp.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);
    }

    const senderAtaA = await getOrCreateAssociatedTokenAccount(
      provider.connection,
      payer,
      mintA,
      sender.publicKey
    );
    await mintTo(
      provider.connection,
      payer,
      mintA,
      senderAtaA.address,
      payer.publicKey,
      1_000
    );

    const [reserveAuthorityA] = PublicKey.findProgramAddressSync(
      [Buffer.from("reserve_auth"), mintA.toBuffer()],
      program.programId
    );
    const reserveAtaA = getAssociatedTokenAddressSync(mintA, reserveAuthorityA, true);
    const recipientAtaB = getAssociatedTokenAddressSync(mintB, recipient.publicKey);

    let threw = false;
    try {
      await program.methods
        .swapAndSend(new BN(100), new BN(99_999)) // demand far more than 200
        .accounts({
          price: pricePda,
          inMint: mintA,
          outMint: mintB,
          inReserveAuthority: reserveAuthorityA,
          outReserveAuthority: reserveAuthorityB,
          inReserveAta: reserveAtaA,
          outReserveAta: reserveAtaB,
          senderInAta: senderAtaA.address,
          recipientOutAta: recipientAtaB,
          recipient: recipient.publicKey,
          sender: sender.publicKey,
          tokenProgram: TOKEN_PROGRAM_ID,
          associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
          systemProgram: SystemProgram.programId,
        })
        .signers([sender])
        .rpc();
    } catch (e) {
      threw = true;
    }
    expect(threw).to.equal(true, "expected slippage rejection");
  });
});
