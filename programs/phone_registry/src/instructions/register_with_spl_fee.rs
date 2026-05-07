use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_interface::{self, Mint, TokenAccount, TokenInterface, TransferChecked};

use crate::error::ErrorCode;
use crate::state::{
    attestation_kind, validate_e164, PhoneRecord, RegistryConfig, ReverseIndex,
    ACCEPTED_MINTS_MAX, PHONE_E164_MAX, PHONE_RECORD_RESERVED,
};

/// Register a phone paying the configured SPL fee from owner's ATA to the
/// treasury's ATA (init_if_needed). Heavy accounts boxed onto the heap to
/// stay under Solana's SBF stack budget. Polymorphic via token_interface
/// so this works on classic SPL Token and Token-2022 mints.
#[derive(Accounts)]
#[instruction(phone_e164: String)]
pub struct RegisterWithSplFee<'info> {
    #[account(
        seeds = [b"config"],
        bump = config.bump,
        has_one = treasury,
        constraint = fee_mint.key() == config.fee_spl_mint @ ErrorCode::WrongFeeMint,
        constraint = config.fee_spl_mint != Pubkey::default() @ ErrorCode::SplFeeNotConfigured,
    )]
    pub config: Box<Account<'info, RegistryConfig>>,

    #[account(
        init,
        payer = owner,
        space = 8 + PhoneRecord::INIT_SPACE,
        seeds = [b"phone", phone_e164.as_bytes()],
        bump,
    )]
    pub phone_record: Box<Account<'info, PhoneRecord>>,

    #[account(
        init,
        payer = owner,
        space = 8 + ReverseIndex::INIT_SPACE,
        seeds = [b"by_owner", owner.key().as_ref()],
        bump,
    )]
    pub reverse_index: Box<Account<'info, ReverseIndex>>,

    pub fee_mint: Box<InterfaceAccount<'info, Mint>>,

    /// CHECK: Verified via has_one = treasury on config.
    pub treasury: UncheckedAccount<'info>,

    #[account(
        init_if_needed,
        payer = owner,
        associated_token::mint = fee_mint,
        associated_token::authority = treasury,
        associated_token::token_program = token_program,
    )]
    pub treasury_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        mut,
        associated_token::mint = fee_mint,
        associated_token::authority = owner,
        associated_token::token_program = token_program,
    )]
    pub owner_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(mut)]
    pub owner: Signer<'info>,

    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<RegisterWithSplFee>,
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

    let fee = ctx.accounts.config.registration_fee_spl_amount;
    if fee > 0 {
        let cpi = CpiContext::new(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.owner_ata.to_account_info(),
                mint: ctx.accounts.fee_mint.to_account_info(),
                to: ctx.accounts.treasury_ata.to_account_info(),
                authority: ctx.accounts.owner.to_account_info(),
            },
        );
        token_interface::transfer_checked(cpi, fee, ctx.accounts.fee_mint.decimals)?;
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
