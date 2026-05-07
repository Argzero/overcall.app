use anchor_lang::prelude::*;
use anchor_lang::solana_program::{program::invoke, system_instruction};

use crate::error::ErrorCode;
use crate::state::{PhoneRecord, RegistryConfig};

#[derive(Accounts)]
#[instruction(phone_e164: String)]
pub struct PaySol<'info> {
    #[account(
        seeds = [b"config"],
        bump = config.bump,
        has_one = treasury,
    )]
    pub config: Account<'info, RegistryConfig>,

    #[account(
        seeds = [b"phone", phone_e164.as_bytes()],
        bump = phone_record.bump,
    )]
    pub phone_record: Account<'info, PhoneRecord>,

    /// CHECK: Verified in handler against phone_record.owner.
    #[account(mut)]
    pub recipient: UncheckedAccount<'info>,

    /// CHECK: Verified via has_one = treasury on config.
    #[account(mut)]
    pub treasury: UncheckedAccount<'info>,

    #[account(mut)]
    pub sender: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<PaySol>, _phone_e164: String, amount: u64) -> Result<()> {
    require!(!ctx.accounts.config.paused, ErrorCode::RegistryPaused);
    require!(amount > 0, ErrorCode::AmountIsZero);
    require_keys_eq!(
        ctx.accounts.phone_record.owner,
        ctx.accounts.recipient.key(),
        ErrorCode::RecipientMismatch
    );

    let bps = ctx.accounts.config.payment_fee_bps as u128;
    let fee = ((amount as u128) * bps / 10_000u128) as u64;

    // Transfer amount (full nominal) to recipient.
    let to_recipient = system_instruction::transfer(
        &ctx.accounts.sender.key(),
        &ctx.accounts.recipient.key(),
        amount,
    );
    invoke(
        &to_recipient,
        &[
            ctx.accounts.sender.to_account_info(),
            ctx.accounts.recipient.to_account_info(),
            ctx.accounts.system_program.to_account_info(),
        ],
    )?;

    // Transfer additive fee to treasury (skipped if rounded to 0).
    if fee > 0 {
        let to_treasury = system_instruction::transfer(
            &ctx.accounts.sender.key(),
            &ctx.accounts.treasury.key(),
            fee,
        );
        invoke(
            &to_treasury,
            &[
                ctx.accounts.sender.to_account_info(),
                ctx.accounts.treasury.to_account_info(),
                ctx.accounts.system_program.to_account_info(),
            ],
        )?;
    }

    emit!(PaymentEvent {
        sender: ctx.accounts.sender.key(),
        recipient: ctx.accounts.recipient.key(),
        phone_record: ctx.accounts.phone_record.key(),
        mint: Pubkey::default(), // sentinel: native SOL
        amount,
        fee,
        ts: Clock::get()?.unix_timestamp,
    });

    Ok(())
}

/// Emitted by both pay_sol and pay_spl_direct so off-chain consumers
/// (the bubble's recipient-variant websocket subscription) can render
/// "Received $X from +1 (555) ..." regardless of mint.
#[event]
pub struct PaymentEvent {
    pub sender: Pubkey,
    pub recipient: Pubkey,
    pub phone_record: Pubkey,
    pub mint: Pubkey, // Pubkey::default() = native SOL
    pub amount: u64,
    pub fee: u64,
    pub ts: i64,
}
