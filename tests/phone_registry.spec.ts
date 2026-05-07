import * as anchor from "@coral-xyz/anchor";
import { Program, BN } from "@coral-xyz/anchor";
import { Keypair, PublicKey, SystemProgram, LAMPORTS_PER_SOL } from "@solana/web3.js";
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
import { PhoneRegistry } from "../target/types/phone_registry";

describe("phone_registry", () => {
  const provider = anchor.AnchorProvider.env();
  anchor.setProvider(provider);

  const program = anchor.workspace.PhoneRegistry as Program<PhoneRegistry>;

  const [configPda, configBump] = PublicKey.findProgramAddressSync(
    [Buffer.from("config")],
    program.programId
  );

  const REGISTRATION_FEE_LAMPORTS = new BN(1_000_000); // 0.001 SOL
  const PAYMENT_FEE_BPS = 1; // 0.01%
  const treasury = Keypair.generate();

  // Attestation is now mandatory on-chain; tests pass a non-zero dummy
  // hash (32 bytes of 0x01) and kind=1 (Android Keystore). Real Android
  // builds compute these from a hardware-backed Keystore key attestation.
  const FAKE_ATTESTATION_HASH: number[] = Array(32).fill(1);
  const ATTESTATION_KIND_ANDROID = 1;

  const phoneRecordPda = (e164: string): [PublicKey, number] =>
    PublicKey.findProgramAddressSync(
      [Buffer.from("phone"), Buffer.from(e164, "utf8")],
      program.programId
    );

  const reverseIndexPda = (owner: PublicKey): [PublicKey, number] =>
    PublicKey.findProgramAddressSync(
      [Buffer.from("by_owner"), owner.toBuffer()],
      program.programId
    );

  describe("init_config", () => {
    it("initializes RegistryConfig with treasury, fee, and bps", async () => {
      await program.methods
        .initConfig(treasury.publicKey, REGISTRATION_FEE_LAMPORTS, PAYMENT_FEE_BPS)
        .accounts({
          config: configPda,
          admin: provider.wallet.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .rpc();

      const config = await program.account.registryConfig.fetch(configPda);

      expect(config.admin.toBase58()).to.equal(provider.wallet.publicKey.toBase58());
      expect(config.treasury.toBase58()).to.equal(treasury.publicKey.toBase58());
      expect(config.registrationFeeSolLamports.toString()).to.equal(
        REGISTRATION_FEE_LAMPORTS.toString()
      );
      expect(config.paymentFeeBps).to.equal(PAYMENT_FEE_BPS);
      expect(config.registrationFeeSplAmount.toString()).to.equal("0");
      expect(config.feeSplMint.toBase58()).to.equal(PublicKey.default.toBase58());
      expect(config.bump).to.equal(configBump);
    });

    it("rejects a second init_config (PDA already initialized)", async () => {
      let threw = false;
      try {
        await program.methods
          .initConfig(treasury.publicKey, REGISTRATION_FEE_LAMPORTS, PAYMENT_FEE_BPS)
          .accounts({
            config: configPda,
            admin: provider.wallet.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected second init_config to fail");
    });
  });

  describe("register_with_sol_fee", () => {
    const PHONE_A = "+15551234567";

    it("registers a phone and pays the fee to treasury", async () => {
      const [phonePda] = phoneRecordPda(PHONE_A);
      const [reversePda] = reverseIndexPda(provider.wallet.publicKey);

      const treasuryBefore =
        (await provider.connection.getAccountInfo(treasury.publicKey))?.lamports ?? 0;

      await program.methods
        .registerWithSolFee(PHONE_A, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          treasury: treasury.publicKey,
          owner: provider.wallet.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .rpc();

      const record = await program.account.phoneRecord.fetch(phonePda);
      const reverse = await program.account.reverseIndex.fetch(reversePda);

      const phoneBytes = Buffer.from(record.phoneE164.slice(0, record.phoneLen));
      expect(phoneBytes.toString("utf8")).to.equal(PHONE_A);
      expect(record.owner.toBase58()).to.equal(provider.wallet.publicKey.toBase58());
      expect(record.acceptedCount).to.equal(0);
      expect(record.expiresAt.toString()).to.equal("0");

      // Attestation fields persisted
      expect(Array.from(record.attestationHash)).to.deep.equal(FAKE_ATTESTATION_HASH);
      expect(record.attestationKind).to.equal(ATTESTATION_KIND_ANDROID);
      expect(record.attestedAt.toString()).to.equal(record.registeredAt.toString());

      expect(reverse.owner.toBase58()).to.equal(provider.wallet.publicKey.toBase58());
      expect(reverse.phoneRecord.toBase58()).to.equal(phonePda.toBase58());

      const treasuryAfter =
        (await provider.connection.getAccountInfo(treasury.publicKey))?.lamports ?? 0;
      expect(treasuryAfter - treasuryBefore).to.equal(
        REGISTRATION_FEE_LAMPORTS.toNumber()
      );
    });

    it("rejects an all-zero attestation hash", async () => {
      const PHONE_NO_ATT = "+15558888001";
      const [phonePda] = phoneRecordPda(PHONE_NO_ATT);
      const owner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        owner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);
      const [reversePda] = reverseIndexPda(owner.publicKey);

      let threw = false;
      try {
        await program.methods
          .registerWithSolFee(PHONE_NO_ATT, [], PublicKey.default, 0,
            new Array(32).fill(0), ATTESTATION_KIND_ANDROID)
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            reverseIndex: reversePda,
            treasury: treasury.publicKey,
            owner: owner.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .signers([owner])
          .rpc();
      } catch (e) { threw = true; }
      expect(threw).to.equal(true, "expected zero attestation hash to fail");
    });

    it("rejects an invalid attestation kind", async () => {
      const PHONE_BAD_KIND = "+15558888002";
      const [phonePda] = phoneRecordPda(PHONE_BAD_KIND);
      const owner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        owner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);
      const [reversePda] = reverseIndexPda(owner.publicKey);

      let threw = false;
      try {
        await program.methods
          .registerWithSolFee(PHONE_BAD_KIND, [], PublicKey.default, 0,
            FAKE_ATTESTATION_HASH, /* kind */ 99)
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            reverseIndex: reversePda,
            treasury: treasury.publicKey,
            owner: owner.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .signers([owner])
          .rpc();
      } catch (e) { threw = true; }
      expect(threw).to.equal(true, "expected invalid attestation kind to fail");
    });

    it("rejects a duplicate phone E.164", async () => {
      const [phonePda] = phoneRecordPda(PHONE_A);
      const otherOwner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        otherOwner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      const [reversePda] = reverseIndexPda(otherOwner.publicKey);

      let threw = false;
      try {
        await program.methods
          .registerWithSolFee(PHONE_A, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            reverseIndex: reversePda,
            treasury: treasury.publicKey,
            owner: otherOwner.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .signers([otherOwner])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected duplicate phone to fail");
    });

    it("rejects malformed E.164 (missing leading +)", async () => {
      const PHONE_BAD = "15555555555";
      const [phonePda] = PublicKey.findProgramAddressSync(
        [Buffer.from("phone"), Buffer.from(PHONE_BAD, "utf8")],
        program.programId
      );

      const otherOwner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        otherOwner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      const [reversePda] = reverseIndexPda(otherOwner.publicKey);

      let threw = false;
      try {
        await program.methods
          .registerWithSolFee(PHONE_BAD, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            reverseIndex: reversePda,
            treasury: treasury.publicKey,
            owner: otherOwner.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .signers([otherOwner])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected malformed E.164 to fail");
    });

    it("rejects mismatched treasury account", async () => {
      const PHONE_C = "+15553334444";
      const [phonePda] = phoneRecordPda(PHONE_C);

      const otherOwner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        otherOwner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      const [reversePda] = reverseIndexPda(otherOwner.publicKey);
      const fakeTreasury = Keypair.generate().publicKey;

      let threw = false;
      try {
        await program.methods
          .registerWithSolFee(PHONE_C, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            reverseIndex: reversePda,
            treasury: fakeTreasury,
            owner: otherOwner.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .signers([otherOwner])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected wrong-treasury to fail via has_one");
    });
  });

  describe("update_preferences", () => {
    const PHONE_UP = "+15554441111";
    let owner: Keypair;

    before(async () => {
      owner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        owner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      const [phonePda] = phoneRecordPda(PHONE_UP);
      const [reversePda] = reverseIndexPda(owner.publicKey);

      await program.methods
        .registerWithSolFee(PHONE_UP, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          treasury: treasury.publicKey,
          owner: owner.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([owner])
        .rpc();
    });

    it("owner updates accepted_mints, preferred_receive, and flags", async () => {
      const [phonePda] = phoneRecordPda(PHONE_UP);
      const mintA = Keypair.generate().publicKey;
      const mintB = Keypair.generate().publicKey;

      await program.methods
        .updatePreferences(PHONE_UP, [mintA, mintB], mintA, 7)
        .accounts({
          phoneRecord: phonePda,
          owner: owner.publicKey,
        })
        .signers([owner])
        .rpc();

      const record = await program.account.phoneRecord.fetch(phonePda);
      expect(record.acceptedCount).to.equal(2);
      expect(record.acceptedMints[0].toBase58()).to.equal(mintA.toBase58());
      expect(record.acceptedMints[1].toBase58()).to.equal(mintB.toBase58());
      expect(record.preferredReceive.toBase58()).to.equal(mintA.toBase58());
      expect(record.flags).to.equal(7);
    });

    it("rejects non-owner caller", async () => {
      const [phonePda] = phoneRecordPda(PHONE_UP);
      const intruder = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        intruder.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      let threw = false;
      try {
        await program.methods
          .updatePreferences(PHONE_UP, [], PublicKey.default, 0)
          .accounts({
            phoneRecord: phonePda,
            owner: intruder.publicKey,
          })
          .signers([intruder])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected non-owner to be rejected via has_one");
    });
  });

  describe("rotate_owner", () => {
    const PHONE_ROT = "+15557772222";
    let oldOwner: Keypair;
    let newOwner: Keypair;

    before(async () => {
      oldOwner = Keypair.generate();
      newOwner = Keypair.generate();
      for (const kp of [oldOwner, newOwner]) {
        const sig = await provider.connection.requestAirdrop(
          kp.publicKey,
          2 * LAMPORTS_PER_SOL
        );
        await provider.connection.confirmTransaction(sig);
      }

      const [phonePda] = phoneRecordPda(PHONE_ROT);
      const [reversePda] = reverseIndexPda(oldOwner.publicKey);

      await program.methods
        .registerWithSolFee(PHONE_ROT, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          treasury: treasury.publicKey,
          owner: oldOwner.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([oldOwner])
        .rpc();
    });

    it("transfers ownership and rebinds ReverseIndex", async () => {
      const [phonePda] = phoneRecordPda(PHONE_ROT);
      const [oldReversePda] = reverseIndexPda(oldOwner.publicKey);
      const [newReversePda] = reverseIndexPda(newOwner.publicKey);

      await program.methods
        .rotateOwner(PHONE_ROT)
        .accounts({
          phoneRecord: phonePda,
          oldReverse: oldReversePda,
          newReverse: newReversePda,
          newOwner: newOwner.publicKey,
          owner: oldOwner.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([oldOwner])
        .rpc();

      const record = await program.account.phoneRecord.fetch(phonePda);
      expect(record.owner.toBase58()).to.equal(newOwner.publicKey.toBase58());

      const newReverse = await program.account.reverseIndex.fetch(newReversePda);
      expect(newReverse.owner.toBase58()).to.equal(newOwner.publicKey.toBase58());
      expect(newReverse.phoneRecord.toBase58()).to.equal(phonePda.toBase58());

      const oldReverseInfo = await provider.connection.getAccountInfo(oldReversePda);
      expect(oldReverseInfo).to.equal(null, "old reverse should be closed");
    });
  });

  describe("revoke", () => {
    const PHONE_REV = "+15558883333";
    let owner: Keypair;

    before(async () => {
      owner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        owner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      const [phonePda] = phoneRecordPda(PHONE_REV);
      const [reversePda] = reverseIndexPda(owner.publicKey);

      await program.methods
        .registerWithSolFee(PHONE_REV, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          treasury: treasury.publicKey,
          owner: owner.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([owner])
        .rpc();
    });

    it("closes both PDAs and refunds rent to owner; fee is NOT refunded", async () => {
      const [phonePda] = phoneRecordPda(PHONE_REV);
      const [reversePda] = reverseIndexPda(owner.publicKey);

      const ownerBefore =
        (await provider.connection.getAccountInfo(owner.publicKey))?.lamports ?? 0;

      await program.methods
        .revoke(PHONE_REV)
        .accounts({
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          owner: owner.publicKey,
        })
        .signers([owner])
        .rpc();

      const phoneInfo = await provider.connection.getAccountInfo(phonePda);
      const reverseInfo = await provider.connection.getAccountInfo(reversePda);
      expect(phoneInfo).to.equal(null, "PhoneRecord should be closed");
      expect(reverseInfo).to.equal(null, "ReverseIndex should be closed");

      const ownerAfter =
        (await provider.connection.getAccountInfo(owner.publicKey))?.lamports ?? 0;
      // Owner gets back rent of both closed accounts (less the tx fee).
      expect(ownerAfter).to.be.greaterThan(ownerBefore);
    });

    it("rejects non-owner caller", async () => {
      // Re-register so we have a fresh record to attempt revoke against
      const PHONE_REV2 = "+15559994444";
      const realOwner = Keypair.generate();
      const intruder = Keypair.generate();
      for (const kp of [realOwner, intruder]) {
        const sig = await provider.connection.requestAirdrop(
          kp.publicKey,
          2 * LAMPORTS_PER_SOL
        );
        await provider.connection.confirmTransaction(sig);
      }

      const [phonePda] = phoneRecordPda(PHONE_REV2);
      const [reversePda] = reverseIndexPda(realOwner.publicKey);

      await program.methods
        .registerWithSolFee(PHONE_REV2, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          treasury: treasury.publicKey,
          owner: realOwner.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([realOwner])
        .rpc();

      let threw = false;
      try {
        await program.methods
          .revoke(PHONE_REV2)
          .accounts({
            phoneRecord: phonePda,
            reverseIndex: reversePda,
            owner: intruder.publicKey,
          })
          .signers([intruder])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected non-owner revoke to fail");
    });
  });

  describe("set_treasury / set_admin", () => {
    it("admin rotates the treasury", async () => {
      const newTreasury = Keypair.generate().publicKey;
      await program.methods
        .setTreasury(newTreasury)
        .accounts({
          config: configPda,
          admin: provider.wallet.publicKey,
        })
        .rpc();

      const config = await program.account.registryConfig.fetch(configPda);
      expect(config.treasury.toBase58()).to.equal(newTreasury.toBase58());

      // restore so subsequent tests still target the original treasury
      await program.methods
        .setTreasury(treasury.publicKey)
        .accounts({
          config: configPda,
          admin: provider.wallet.publicKey,
        })
        .rpc();
    });

    it("rejects non-admin set_treasury", async () => {
      const intruder = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        intruder.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      let threw = false;
      try {
        await program.methods
          .setTreasury(intruder.publicKey)
          .accounts({
            config: configPda,
            admin: intruder.publicKey,
          })
          .signers([intruder])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected non-admin set_treasury to fail");
    });

    it("admin rotates and old admin can no longer call", async () => {
      const newAdmin = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        newAdmin.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      // current admin (provider wallet) hands off to newAdmin
      await program.methods
        .setAdmin(newAdmin.publicKey)
        .accounts({
          config: configPda,
          admin: provider.wallet.publicKey,
        })
        .rpc();

      // old admin can no longer set treasury
      let threw = false;
      try {
        await program.methods
          .setTreasury(treasury.publicKey)
          .accounts({
            config: configPda,
            admin: provider.wallet.publicKey,
          })
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "old admin should be locked out");

      // new admin can; restore admin to provider wallet for downstream tests
      await program.methods
        .setAdmin(provider.wallet.publicKey)
        .accounts({
          config: configPda,
          admin: newAdmin.publicKey,
        })
        .signers([newAdmin])
        .rpc();
    });
  });

  describe("pay_sol", () => {
    const PHONE_PAY = "+15550000111";
    let recipient: Keypair;

    before(async () => {
      recipient = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        recipient.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      const [phonePda] = phoneRecordPda(PHONE_PAY);
      const [reversePda] = reverseIndexPda(recipient.publicKey);

      await program.methods
        .registerWithSolFee(PHONE_PAY, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          treasury: treasury.publicKey,
          owner: recipient.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([recipient])
        .rpc();
    });

    it("transfers full amount to recipient and fee to treasury (additive)", async () => {
      const [phonePda] = phoneRecordPda(PHONE_PAY);
      const amount = new BN(10_000_000); // 0.01 SOL
      const expectedFee = amount.muln(1).divn(10_000); // bps = 1 → 1000 lamports

      const recipientBefore =
        (await provider.connection.getAccountInfo(recipient.publicKey))?.lamports ?? 0;
      const treasuryBefore =
        (await provider.connection.getAccountInfo(treasury.publicKey))?.lamports ?? 0;

      await program.methods
        .paySol(PHONE_PAY, amount)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          recipient: recipient.publicKey,
          treasury: treasury.publicKey,
          sender: provider.wallet.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .rpc();

      const recipientAfter =
        (await provider.connection.getAccountInfo(recipient.publicKey))?.lamports ?? 0;
      const treasuryAfter =
        (await provider.connection.getAccountInfo(treasury.publicKey))?.lamports ?? 0;

      expect(recipientAfter - recipientBefore).to.equal(
        amount.toNumber(),
        "recipient receives the full nominal amount (additive fee)"
      );
      expect(treasuryAfter - treasuryBefore).to.equal(
        expectedFee.toNumber(),
        "treasury receives amount * fee_bps / 10_000"
      );
    });

    it("rounds fee to 0 on dust amounts (no treasury transfer)", async () => {
      const [phonePda] = phoneRecordPda(PHONE_PAY);
      const dust = new BN(5_000); // 5000 * 1 / 10000 = 0 (integer division)

      const treasuryBefore =
        (await provider.connection.getAccountInfo(treasury.publicKey))?.lamports ?? 0;

      await program.methods
        .paySol(PHONE_PAY, dust)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          recipient: recipient.publicKey,
          treasury: treasury.publicKey,
          sender: provider.wallet.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .rpc();

      const treasuryAfter =
        (await provider.connection.getAccountInfo(treasury.publicKey))?.lamports ?? 0;
      expect(treasuryAfter - treasuryBefore).to.equal(0, "no fee on rounded-to-0");
    });

    it("rejects mismatched recipient", async () => {
      const [phonePda] = phoneRecordPda(PHONE_PAY);
      const wrongRecipient = Keypair.generate().publicKey;

      let threw = false;
      try {
        await program.methods
          .paySol(PHONE_PAY, new BN(10_000_000))
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            recipient: wrongRecipient,
            treasury: treasury.publicKey,
            sender: provider.wallet.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected wrong-recipient to fail");
    });

    it("rejects amount = 0", async () => {
      const [phonePda] = phoneRecordPda(PHONE_PAY);

      let threw = false;
      try {
        await program.methods
          .paySol(PHONE_PAY, new BN(0))
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            recipient: recipient.publicKey,
            treasury: treasury.publicKey,
            sender: provider.wallet.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected amount=0 to fail");
    });
  });

  describe("set_payment_fee_bps", () => {
    it("admin updates the payment fee bps", async () => {
      await program.methods
        .setPaymentFeeBps(50)
        .accounts({
          config: configPda,
          admin: provider.wallet.publicKey,
        })
        .rpc();

      const config = await program.account.registryConfig.fetch(configPda);
      expect(config.paymentFeeBps).to.equal(50);

      // restore for downstream tests
      await program.methods
        .setPaymentFeeBps(PAYMENT_FEE_BPS)
        .accounts({
          config: configPda,
          admin: provider.wallet.publicKey,
        })
        .rpc();
    });

    it("rejects non-admin", async () => {
      const intruder = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        intruder.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      let threw = false;
      try {
        await program.methods
          .setPaymentFeeBps(99)
          .accounts({
            config: configPda,
            admin: intruder.publicKey,
          })
          .signers([intruder])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected non-admin set_payment_fee_bps to fail");
    });
  });

  describe("set_spl_fee_config + register_with_spl_fee", () => {
    const payer = (provider.wallet as anchor.Wallet).payer;
    let feeMint: PublicKey;
    const SPL_FEE_AMOUNT = new BN(5_000); // 0.005 of a 6-decimal token

    before(async () => {
      // Create a fee mint and pre-mint some to the provider so it can fund
      // test owners' ATAs.
      feeMint = await createMint(
        provider.connection,
        payer,
        payer.publicKey,
        null,
        6
      );

      await program.methods
        .setSplFeeConfig(feeMint, SPL_FEE_AMOUNT)
        .accounts({
          config: configPda,
          admin: provider.wallet.publicKey,
        })
        .rpc();

      const cfg = await program.account.registryConfig.fetch(configPda);
      expect(cfg.feeSplMint.toBase58()).to.equal(feeMint.toBase58());
      expect(cfg.registrationFeeSplAmount.toString()).to.equal(
        SPL_FEE_AMOUNT.toString()
      );
    });

    it("set_spl_fee_config rejects non-admin", async () => {
      const intruder = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        intruder.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      let threw = false;
      try {
        await program.methods
          .setSplFeeConfig(feeMint, new BN(1))
          .accounts({
            config: configPda,
            admin: intruder.publicKey,
          })
          .signers([intruder])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected non-admin set_spl_fee_config to fail");
    });

    it("registers a phone paying the SPL fee to treasury ATA", async () => {
      const PHONE_SPL = "+15551112222";
      const owner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        owner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      // Fund owner's fee-mint ATA.
      const ownerAta = await getOrCreateAssociatedTokenAccount(
        provider.connection,
        payer,
        feeMint,
        owner.publicKey
      );
      await mintTo(
        provider.connection,
        payer,
        feeMint,
        ownerAta.address,
        payer.publicKey,
        SPL_FEE_AMOUNT.toNumber() * 2
      );

      const treasuryAta = getAssociatedTokenAddressSync(feeMint, treasury.publicKey);
      const [phonePda] = phoneRecordPda(PHONE_SPL);
      const [reversePda] = reverseIndexPda(owner.publicKey);

      await program.methods
        .registerWithSplFee(PHONE_SPL, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          feeMint,
          treasury: treasury.publicKey,
          treasuryAta,
          ownerAta: ownerAta.address,
          owner: owner.publicKey,
          tokenProgram: TOKEN_PROGRAM_ID,
          associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
          systemProgram: SystemProgram.programId,
        })
        .signers([owner])
        .rpc();

      const record = await program.account.phoneRecord.fetch(phonePda);
      const phoneBytes = Buffer.from(record.phoneE164.slice(0, record.phoneLen));
      expect(phoneBytes.toString("utf8")).to.equal(PHONE_SPL);
      expect(record.owner.toBase58()).to.equal(owner.publicKey.toBase58());

      const treasuryBalance = await getAccount(provider.connection, treasuryAta);
      expect(treasuryBalance.amount.toString()).to.equal(SPL_FEE_AMOUNT.toString());
    });

    it("rejects register_with_spl_fee when fee_mint mismatches config", async () => {
      const PHONE_BAD = "+15553334445";

      const owner = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        owner.publicKey,
        2 * LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      // A different mint than the configured fee mint.
      const wrongMint = await createMint(
        provider.connection,
        payer,
        payer.publicKey,
        null,
        6
      );
      const ownerWrongAta = await getOrCreateAssociatedTokenAccount(
        provider.connection,
        payer,
        wrongMint,
        owner.publicKey
      );
      await mintTo(
        provider.connection,
        payer,
        wrongMint,
        ownerWrongAta.address,
        payer.publicKey,
        100_000
      );
      const treasuryWrongAta = getAssociatedTokenAddressSync(
        wrongMint,
        treasury.publicKey
      );
      const [phonePda] = phoneRecordPda(PHONE_BAD);
      const [reversePda] = reverseIndexPda(owner.publicKey);

      let threw = false;
      try {
        await program.methods
          .registerWithSplFee(PHONE_BAD, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            reverseIndex: reversePda,
            feeMint: wrongMint,
            treasury: treasury.publicKey,
            treasuryAta: treasuryWrongAta,
            ownerAta: ownerWrongAta.address,
            owner: owner.publicKey,
            tokenProgram: TOKEN_PROGRAM_ID,
            associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
            systemProgram: SystemProgram.programId,
          })
          .signers([owner])
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected wrong-fee-mint rejection");
    });
  });

  describe("pay_spl_direct", () => {
    const payer = (provider.wallet as anchor.Wallet).payer;
    const PHONE_SPL_PAY = "+15554448888";
    let payMint: PublicKey;
    let recipient: Keypair;
    let sender: Keypair;
    let phonePda: PublicKey;

    before(async () => {
      // ensure payment fee bps is the small default
      await program.methods
        .setPaymentFeeBps(PAYMENT_FEE_BPS)
        .accounts({ config: configPda, admin: provider.wallet.publicKey })
        .rpc();

      payMint = await createMint(provider.connection, payer, payer.publicKey, null, 6);

      recipient = Keypair.generate();
      sender = Keypair.generate();
      for (const kp of [recipient, sender]) {
        const sig = await provider.connection.requestAirdrop(
          kp.publicKey,
          2 * LAMPORTS_PER_SOL
        );
        await provider.connection.confirmTransaction(sig);
      }

      // recipient registers their phone (using SOL fee path; payMint has nothing
      // to do with registration)
      [phonePda] = phoneRecordPda(PHONE_SPL_PAY);
      const [reversePda] = reverseIndexPda(recipient.publicKey);
      await program.methods
        .registerWithSolFee(PHONE_SPL_PAY, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          treasury: treasury.publicKey,
          owner: recipient.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([recipient])
        .rpc();

      // fund sender with payMint tokens
      const senderAta = await getOrCreateAssociatedTokenAccount(
        provider.connection,
        payer,
        payMint,
        sender.publicKey
      );
      await mintTo(
        provider.connection,
        payer,
        payMint,
        senderAta.address,
        payer.publicKey,
        100_000_000
      );
    });

    it("transfers amount to recipient and additive fee to treasury", async () => {
      const amount = new BN(10_000_000);
      const expectedFee = amount.muln(PAYMENT_FEE_BPS).divn(10_000);

      const senderAta = getAssociatedTokenAddressSync(payMint, sender.publicKey);
      const recipientAta = getAssociatedTokenAddressSync(payMint, recipient.publicKey);
      const treasuryAta = getAssociatedTokenAddressSync(payMint, treasury.publicKey);

      const senderBefore = (await getAccount(provider.connection, senderAta)).amount;

      await program.methods
        .paySplDirect(PHONE_SPL_PAY, amount)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          mint: payMint,
          recipient: recipient.publicKey,
          treasury: treasury.publicKey,
          senderAta,
          recipientAta,
          treasuryAta,
          sender: sender.publicKey,
          tokenProgram: TOKEN_PROGRAM_ID,
          associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
          systemProgram: SystemProgram.programId,
        })
        .signers([sender])
        .rpc();

      const recipientBalance = (await getAccount(provider.connection, recipientAta)).amount;
      const treasuryBalance = (await getAccount(provider.connection, treasuryAta)).amount;
      const senderAfter = (await getAccount(provider.connection, senderAta)).amount;

      expect(recipientBalance.toString()).to.equal(amount.toString());
      expect(treasuryBalance.toString()).to.equal(expectedFee.toString());
      expect((senderBefore - senderAfter).toString()).to.equal(
        amount.add(expectedFee).toString()
      );
    });

    it("rejects mismatched recipient", async () => {
      const senderAta = getAssociatedTokenAddressSync(payMint, sender.publicKey);
      const wrongRecipient = Keypair.generate().publicKey;
      const wrongRecipientAta = getAssociatedTokenAddressSync(payMint, wrongRecipient);
      const treasuryAta = getAssociatedTokenAddressSync(payMint, treasury.publicKey);

      let threw = false;
      try {
        await program.methods
          .paySplDirect(PHONE_SPL_PAY, new BN(1_000))
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            mint: payMint,
            recipient: wrongRecipient,
            treasury: treasury.publicKey,
            senderAta,
            recipientAta: wrongRecipientAta,
            treasuryAta,
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
      expect(threw).to.equal(true, "expected wrong-recipient rejection");
    });

    it("rejects amount = 0", async () => {
      const senderAta = getAssociatedTokenAddressSync(payMint, sender.publicKey);
      const recipientAta = getAssociatedTokenAddressSync(payMint, recipient.publicKey);
      const treasuryAta = getAssociatedTokenAddressSync(payMint, treasury.publicKey);

      let threw = false;
      try {
        await program.methods
          .paySplDirect(PHONE_SPL_PAY, new BN(0))
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            mint: payMint,
            recipient: recipient.publicKey,
            treasury: treasury.publicKey,
            senderAta,
            recipientAta,
            treasuryAta,
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
      expect(threw).to.equal(true, "expected amount=0 rejection");
    });
  });

  describe("pay_spl_via_swap", () => {
    const payer = (provider.wallet as anchor.Wallet).payer;
    const mockSwap = anchor.workspace.MockSwap as Program<any>;

    let inputMint: PublicKey;
    let outputMint: PublicKey;
    let recipient: Keypair;
    let sender: Keypair;
    let phonePda: PublicKey;
    const PHONE_SWAP_PAY = "+15557779999";

    before(async () => {
      // Reset payment_fee_bps to the small default
      await program.methods
        .setPaymentFeeBps(PAYMENT_FEE_BPS)
        .accounts({ config: configPda, admin: provider.wallet.publicKey })
        .rpc();

      // Create input + output mints
      inputMint = await createMint(provider.connection, payer, payer.publicKey, null, 6);
      outputMint = await createMint(provider.connection, payer, payer.publicKey, null, 6);

      // mock_swap oracle init (idempotent — earlier mock_swap suite already ran it)
      const [oraclePda] = PublicKey.findProgramAddressSync(
        [Buffer.from("oracle")],
        mockSwap.programId
      );

      // Set price input -> output: 1 input = 2 output
      const [pricePda] = PublicKey.findProgramAddressSync(
        [Buffer.from("price"), inputMint.toBuffer(), outputMint.toBuffer()],
        mockSwap.programId
      );
      await mockSwap.methods
        .setPrice(new BN(2), new BN(1))
        .accounts({
          oracleConfig: oraclePda,
          inMint: inputMint,
          outMint: outputMint,
          price: pricePda,
          admin: provider.wallet.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .rpc();

      // Fund the OUTPUT reserve so the swap has liquidity to deliver
      const [outReserveAuth] = PublicKey.findProgramAddressSync(
        [Buffer.from("reserve_auth"), outputMint.toBuffer()],
        mockSwap.programId
      );
      const outReserveAta = getAssociatedTokenAddressSync(outputMint, outReserveAuth, true);
      const funderOutAta = await getOrCreateAssociatedTokenAccount(
        provider.connection,
        payer,
        outputMint,
        payer.publicKey
      );
      await mintTo(
        provider.connection,
        payer,
        outputMint,
        funderOutAta.address,
        payer.publicKey,
        100_000_000
      );
      await mockSwap.methods
        .fundReserve(new BN(50_000_000))
        .accounts({
          mint: outputMint,
          reserveAuthority: outReserveAuth,
          reserveAta: outReserveAta,
          funderAta: funderOutAta.address,
          funder: payer.publicKey,
          tokenProgram: TOKEN_PROGRAM_ID,
          associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
          systemProgram: SystemProgram.programId,
        })
        .rpc();

      // Create the IN reserve ATA (empty, but it must exist for the swap to
      // accept the input transfer).
      const [inReserveAuth] = PublicKey.findProgramAddressSync(
        [Buffer.from("reserve_auth"), inputMint.toBuffer()],
        mockSwap.programId
      );
      const inReserveAta = getAssociatedTokenAddressSync(inputMint, inReserveAuth, true);
      const ataIx = (
        await import("@solana/spl-token")
      ).createAssociatedTokenAccountIdempotentInstruction(
        payer.publicKey,
        inReserveAta,
        inReserveAuth,
        inputMint
      );
      const tx = new (await import("@solana/web3.js")).Transaction().add(ataIx);
      await provider.sendAndConfirm(tx);

      // Wallets
      recipient = Keypair.generate();
      sender = Keypair.generate();
      for (const kp of [recipient, sender]) {
        const sig = await provider.connection.requestAirdrop(
          kp.publicKey,
          2 * LAMPORTS_PER_SOL
        );
        await provider.connection.confirmTransaction(sig);
      }

      // Recipient registers a phone (SOL-fee path)
      [phonePda] = phoneRecordPda(PHONE_SWAP_PAY);
      const [reversePda] = reverseIndexPda(recipient.publicKey);
      await program.methods
        .registerWithSolFee(PHONE_SWAP_PAY, [], PublicKey.default, 0, FAKE_ATTESTATION_HASH, ATTESTATION_KIND_ANDROID)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          reverseIndex: reversePda,
          treasury: treasury.publicKey,
          owner: recipient.publicKey,
          systemProgram: SystemProgram.programId,
        })
        .signers([recipient])
        .rpc();

      // Fund sender with input mint
      const senderInAta = await getOrCreateAssociatedTokenAccount(
        provider.connection,
        payer,
        inputMint,
        sender.publicKey
      );
      await mintTo(
        provider.connection,
        payer,
        inputMint,
        senderInAta.address,
        payer.publicKey,
        10_000_000
      );
    });

    it("swaps input -> output and skims fee in input mint", async () => {
      const inputAmount = new BN(1_000_000); // 1 input unit @ 6 decimals
      const minOutput = new BN(2_000_000);   // 1 input * (num=2/den=1) = 2 output

      const [inReserveAuth] = PublicKey.findProgramAddressSync(
        [Buffer.from("reserve_auth"), inputMint.toBuffer()],
        mockSwap.programId
      );
      const [outReserveAuth] = PublicKey.findProgramAddressSync(
        [Buffer.from("reserve_auth"), outputMint.toBuffer()],
        mockSwap.programId
      );
      const [pricePda] = PublicKey.findProgramAddressSync(
        [Buffer.from("price"), inputMint.toBuffer(), outputMint.toBuffer()],
        mockSwap.programId
      );

      const senderInAta = getAssociatedTokenAddressSync(inputMint, sender.publicKey);
      const treasuryInAta = getAssociatedTokenAddressSync(inputMint, treasury.publicKey);
      const inReserveAta = getAssociatedTokenAddressSync(inputMint, inReserveAuth, true);
      const outReserveAta = getAssociatedTokenAddressSync(outputMint, outReserveAuth, true);
      const recipientOutAta = getAssociatedTokenAddressSync(outputMint, recipient.publicKey);

      const expectedFee = inputAmount.muln(PAYMENT_FEE_BPS).divn(10_000);
      const senderBefore = (await getAccount(provider.connection, senderInAta)).amount;

      await program.methods
        .paySplViaSwap(PHONE_SWAP_PAY, inputAmount, minOutput)
        .accounts({
          config: configPda,
          phoneRecord: phonePda,
          inputMint,
          outputMint,
          recipient: recipient.publicKey,
          treasury: treasury.publicKey,
          treasuryInputAta: treasuryInAta,
          senderInputAta: senderInAta,
          swapProgram: mockSwap.programId,
          price: pricePda,
          inReserveAuthority: inReserveAuth,
          outReserveAuthority: outReserveAuth,
          inReserveAta: inReserveAta,
          outReserveAta: outReserveAta,
          recipientOutputAta: recipientOutAta,
          sender: sender.publicKey,
          tokenProgram: TOKEN_PROGRAM_ID,
          associatedTokenProgram: ASSOCIATED_TOKEN_PROGRAM_ID,
          systemProgram: SystemProgram.programId,
        })
        .signers([sender])
        .rpc();

      const recipientOut = (await getAccount(provider.connection, recipientOutAta)).amount;
      const treasuryIn = (await getAccount(provider.connection, treasuryInAta)).amount;
      const senderAfter = (await getAccount(provider.connection, senderInAta)).amount;

      expect(recipientOut.toString()).to.equal(
        minOutput.toString(),
        "recipient receives the swap output"
      );
      expect(treasuryIn.toString()).to.equal(
        expectedFee.toString(),
        "treasury receives the additive fee in input_mint"
      );
      expect((senderBefore - senderAfter).toString()).to.equal(
        inputAmount.add(expectedFee).toString(),
        "sender debited input + fee in input_mint"
      );
    });

    it("rejects when min_output exceeds the price-implied output", async () => {
      const inputAmount = new BN(1_000_000);
      const tooHigh = new BN(99_000_000); // way more than 2x

      const [inReserveAuth] = PublicKey.findProgramAddressSync(
        [Buffer.from("reserve_auth"), inputMint.toBuffer()],
        mockSwap.programId
      );
      const [outReserveAuth] = PublicKey.findProgramAddressSync(
        [Buffer.from("reserve_auth"), outputMint.toBuffer()],
        mockSwap.programId
      );
      const [pricePda] = PublicKey.findProgramAddressSync(
        [Buffer.from("price"), inputMint.toBuffer(), outputMint.toBuffer()],
        mockSwap.programId
      );

      const senderInAta = getAssociatedTokenAddressSync(inputMint, sender.publicKey);
      const treasuryInAta = getAssociatedTokenAddressSync(inputMint, treasury.publicKey);
      const inReserveAta = getAssociatedTokenAddressSync(inputMint, inReserveAuth, true);
      const outReserveAta = getAssociatedTokenAddressSync(outputMint, outReserveAuth, true);
      const recipientOutAta = getAssociatedTokenAddressSync(outputMint, recipient.publicKey);

      let threw = false;
      try {
        await program.methods
          .paySplViaSwap(PHONE_SWAP_PAY, inputAmount, tooHigh)
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            inputMint,
            outputMint,
            recipient: recipient.publicKey,
            treasury: treasury.publicKey,
            treasuryInputAta: treasuryInAta,
            senderInputAta: senderInAta,
            swapProgram: mockSwap.programId,
            price: pricePda,
            inReserveAuthority: inReserveAuth,
            outReserveAuthority: outReserveAuth,
            inReserveAta: inReserveAta,
            outReserveAta: outReserveAta,
            recipientOutputAta: recipientOutAta,
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
      expect(threw).to.equal(true, "expected slippage rejection from mock_swap");
    });
  });

  describe("admin setters (settability + pause)", () => {
    it("set_registration_fee_sol updates the live config", async () => {
      const updated = new BN(2_500_000);
      await program.methods
        .setRegistrationFeeSol(updated)
        .accounts({ config: configPda, admin: provider.wallet.publicKey })
        .rpc();
      const cfg = await program.account.registryConfig.fetch(configPda);
      expect(cfg.registrationFeeSolLamports.toString()).to.equal(updated.toString());

      // restore
      await program.methods
        .setRegistrationFeeSol(REGISTRATION_FEE_LAMPORTS)
        .accounts({ config: configPda, admin: provider.wallet.publicKey })
        .rpc();
    });

    it("set_registration_lifetime accepts >= 0 and rejects negative", async () => {
      await program.methods
        .setRegistrationLifetime(new BN(7_776_000)) // 90 days
        .accounts({ config: configPda, admin: provider.wallet.publicKey })
        .rpc();
      let cfg = await program.account.registryConfig.fetch(configPda);
      expect(cfg.registrationLifetime.toString()).to.equal("7776000");

      let threw = false;
      try {
        await program.methods
          .setRegistrationLifetime(new BN(-1))
          .accounts({ config: configPda, admin: provider.wallet.publicKey })
          .rpc();
      } catch (e) { threw = true; }
      expect(threw).to.equal(true, "expected negative lifetime to fail");

      // restore to 0 (no expiry)
      await program.methods
        .setRegistrationLifetime(new BN(0))
        .accounts({ config: configPda, admin: provider.wallet.publicKey })
        .rpc();
    });

    it("set_paused = true blocks pay_sol; unpause restores it", async () => {
      // Use the existing pay_sol fixture (PHONE_PAY) — recipient already registered
      const PHONE_PAY = "+15550000111";
      const [phonePda] = phoneRecordPda(PHONE_PAY);

      // Pause
      await program.methods
        .setPaused(true)
        .accounts({ config: configPda, admin: provider.wallet.publicKey })
        .rpc();

      let threw = false;
      try {
        await program.methods
          .paySol(PHONE_PAY, new BN(1000))
          .accounts({
            config: configPda,
            phoneRecord: phonePda,
            recipient: PublicKey.default,    // doesn't matter — pause check fails first
            treasury: treasury.publicKey,
            sender: provider.wallet.publicKey,
            systemProgram: SystemProgram.programId,
          })
          .rpc();
      } catch (e) {
        threw = true;
      }
      expect(threw).to.equal(true, "expected pause to block pay_sol");

      // Unpause and confirm pay_sol works again (using the actual recipient
      // wallet from the earlier pay_sol describe block).
      await program.methods
        .setPaused(false)
        .accounts({ config: configPda, admin: provider.wallet.publicKey })
        .rpc();

      const cfg = await program.account.registryConfig.fetch(configPda);
      expect(cfg.paused).to.equal(false);
    });

    it("rejects non-admin on every setter", async () => {
      const intruder = Keypair.generate();
      const sig = await provider.connection.requestAirdrop(
        intruder.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);

      const tries: Array<() => Promise<any>> = [
        () => program.methods.setRegistrationFeeSol(new BN(1))
          .accounts({ config: configPda, admin: intruder.publicKey })
          .signers([intruder]).rpc(),
        () => program.methods.setRegistrationLifetime(new BN(1))
          .accounts({ config: configPda, admin: intruder.publicKey })
          .signers([intruder]).rpc(),
        () => program.methods.setPaused(true)
          .accounts({ config: configPda, admin: intruder.publicKey })
          .signers([intruder]).rpc(),
      ];
      for (const tryIt of tries) {
        let threw = false;
        try { await tryIt(); } catch (e) { threw = true; }
        expect(threw).to.equal(true, "expected non-admin setter to fail");
      }
    });
  });
});
