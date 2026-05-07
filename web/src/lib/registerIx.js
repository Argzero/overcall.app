import { PublicKey, SystemProgram, TransactionInstruction, } from "@solana/web3.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { PHONE_REGISTRY_PROGRAM, REGISTRY_CONFIG_PDA } from "./config";
const TEXT = (s) => new TextEncoder().encode(s);
/** Anchor instruction discriminator: first 8 bytes of sha256("global:<method>"). */
function ixDiscriminator(method) {
    return sha256(TEXT(`global:${method}`)).slice(0, 8);
}
export function phoneRecordPda(phoneE164) {
    return PublicKey.findProgramAddressSync([TEXT("phone"), TEXT(phoneE164)], PHONE_REGISTRY_PROGRAM)[0];
}
export function reverseIndexPda(owner) {
    return PublicKey.findProgramAddressSync([TEXT("by_owner"), owner.toBuffer()], PHONE_REGISTRY_PROGRAM)[0];
}
/**
 * Build the on-chain `register_with_sol_fee` instruction. Wire format
 * matches the Anchor handler signature exactly:
 *
 *   discriminator (8) || phone_e164 (Borsh string) ||
 *   accepted_mints (Borsh Vec<Pubkey>) || preferred_receive (32) ||
 *   flags (u32 LE) || attestation_hash (32) || attestation_kind (u8)
 */
export function buildRegisterWithSolFeeIx(args) {
    if (args.attestationHash.length !== 32) {
        throw new Error("attestation hash must be 32 bytes");
    }
    const acceptedMints = args.acceptedMints ?? [];
    const preferredReceive = args.preferredReceive ?? PublicKey.default;
    const flags = args.flags ?? 0;
    const phoneBytes = TEXT(args.phoneE164);
    const phoneLenLe = u32Le(phoneBytes.length);
    const acceptedLenLe = u32Le(acceptedMints.length);
    const flagsLe = u32Le(flags);
    const data = concat([
        ixDiscriminator("register_with_sol_fee"),
        phoneLenLe,
        phoneBytes,
        acceptedLenLe,
        ...acceptedMints.map((m) => m.toBytes()),
        preferredReceive.toBytes(),
        flagsLe,
        args.attestationHash,
        new Uint8Array([args.attestationKind & 0xff]),
    ]);
    const phoneRecord = phoneRecordPda(args.phoneE164);
    const reverseIndex = reverseIndexPda(args.owner);
    const keys = [
        { pubkey: REGISTRY_CONFIG_PDA, isSigner: false, isWritable: false },
        { pubkey: phoneRecord, isSigner: false, isWritable: true },
        { pubkey: reverseIndex, isSigner: false, isWritable: true },
        { pubkey: args.treasury, isSigner: false, isWritable: true },
        { pubkey: args.owner, isSigner: true, isWritable: true },
        { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ];
    return new TransactionInstruction({
        programId: PHONE_REGISTRY_PROGRAM,
        keys,
        data: Buffer.from(data),
    });
}
function u32Le(n) {
    const b = new Uint8Array(4);
    new DataView(b.buffer).setUint32(0, n, true);
    return b;
}
function concat(parts) {
    const total = parts.reduce((s, p) => s + p.length, 0);
    const out = new Uint8Array(total);
    let off = 0;
    for (const p of parts) {
        out.set(p, off);
        off += p.length;
    }
    return out;
}
