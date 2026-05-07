use anchor_lang::prelude::*;
use anchor_lang::solana_program::{program::invoke, system_instruction};

use crate::error::ErrorCode;
use crate::state::{
    attestation_kind, validate_e164, PhoneRecord, RegistryConfig, ReverseIndex,
    ACCEPTED_MINTS_MAX, PHONE_E164_MAX, PHONE_RECORD_RESERVED,
};

#[derive(Accounts)]
#[instruction(phone_e164: String)]
pub struct RegisterWithSolFee<'info> {
    #[account(
        seeds = [b"config"],
        bump = config.bump,
        has_one = treasury,
    )]
    pub config: Account<'info, RegistryConfig>,

    #[account(
        init,
        payer = owner,
        space = 8 + PhoneRecord::INIT_SPACE,
        seeds = [b"phone", phone_e164.as_bytes()],
        bump,
    )]
    pub phone_record: Account<'info, PhoneRecord>,

    #[account(
        init,
        payer = owner,
        space = 8 + ReverseIndex::INIT_SPACE,
        seeds = [b"by_owner", owner.key().as_ref()],
        bump,
    )]
    pub reverse_index: Account<'info, ReverseIndex>,

    /// CHECK: Verified via `has_one = treasury` on `config`. Receives the SOL registration fee.
    #[account(mut)]
    pub treasury: UncheckedAccount<'info>,

    #[account(mut)]
    pub owner: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<RegisterWithSolFee>,
    phone_e164: String,
    accepted_mints: Vec<Pubkey>,
    preferred_receive: Pubkey,
    flags: u32,
    attestation_hash: [u8; 32],
    attestation_kind_val: u8,
) -> Result<()> {
    validate_e164(&phone_e164)?;
    require!(
        accepted_mints.len() <= ACCEPTED_MINTS_MAX,
        ErrorCode::TooManyAcceptedMints
    );
    require!(
        matches!(
            attestation_kind_val,
            attestation_kind::ANDROID_KEYSTORE
                | attestation_kind::SEEKER_SEED_VAULT
                | attestation_kind::WEB_DELEGATED
        ),
        ErrorCode::InvalidAttestationKind
    );
    require!(
        attestation_hash != [0u8; 32],
        ErrorCode::AttestationRequired
    );

    let fee = ctx.accounts.config.registration_fee_sol_lamports;
    if fee > 0 {
        let ix = system_instruction::transfer(
            &ctx.accounts.owner.key(),
            &ctx.accounts.treasury.key(),
            fee,
        );
        invoke(
            &ix,
            &[
                ctx.accounts.owner.to_account_info(),
                ctx.accounts.treasury.to_account_info(),
                ctx.accounts.system_program.to_account_info(),
            ],
        )?;
    }

    let record = &mut ctx.accounts.phone_record;
    record.bump = ctx.bumps.phone_record;
    record.phone_e164 = [0u8; PHONE_E164_MAX];
    let phone_bytes = phone_e164.as_bytes();
    record.phone_e164[..phone_bytes.len()].copy_from_slice(phone_bytes);
    record.phone_len = phone_bytes.len() as u8;
    record.owner = ctx.accounts.owner.key();
    record.accepted_mints = [Pubkey::default(); ACCEPTED_MINTS_MAX];
    for (i, mint) in accepted_mints.iter().enumerate() {
        record.accepted_mints[i] = *mint;
    }
    record.accepted_count = accepted_mints.len() as u8;
    record.preferred_receive = preferred_receive;
    record.registered_at = Clock::get()?.unix_timestamp;
    record.expires_at = if ctx.accounts.config.registration_lifetime > 0 {
        record.registered_at + ctx.accounts.config.registration_lifetime
    } else {
        0
    };
    record.flags = flags;
    record.attestation_hash = attestation_hash;
    record.attestation_kind = attestation_kind_val;
    record.attested_at = record.registered_at;
    record.reserved = [0u8; PHONE_RECORD_RESERVED];

    let reverse = &mut ctx.accounts.reverse_index;
    reverse.bump = ctx.bumps.reverse_index;
    reverse.owner = ctx.accounts.owner.key();
    reverse.phone_record = ctx.accounts.phone_record.key();
    reverse.reserved = [0u8; 64];

    Ok(())
}
