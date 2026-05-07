use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_interface::{self, Mint, TokenAccount, TokenInterface, TransferChecked};

use crate::error::ErrorCode;
use crate::instructions::pay_sol::PaymentEvent;
use crate::state::{PhoneRecord, RegistryConfig};

/// Same-mint SPL payment with additive fee skim.
/// Recipient receives `amount` in `mint`; treasury receives
/// `amount * payment_fee_bps / 10_000` in the same mint.
///
/// Polymorphic via token_interface so this works on classic SPL Token
/// and Token-2022 mints (including 2022 with transfer-fee extensions —
/// transfer_checked is the safe path).
#[derive(Accounts)]
#[instruction(phone_e164: String)]
pub struct PaySplDirect<'info> {
    #[account(
        seeds = [b"config"],
        bump = config.bump,
        has_one = treasury,
    )]
    pub config: Box<Account<'info, RegistryConfig>>,

    #[account(
        seeds = [b"phone", phone_e164.as_bytes()],
        bump = phone_record.bump,
    )]
    pub phone_record: Box<Account<'info, PhoneRecord>>,

    pub mint: Box<InterfaceAccount<'info, Mint>>,

    /// CHECK: Verified in handler against phone_record.owner.
    pub recipient: UncheckedAccount<'info>,

    /// CHECK: Verified via has_one = treasury on config.
    pub treasury: UncheckedAccount<'info>,

    #[account(
        mut,
        associated_token::mint = mint,
        associated_token::authority = sender,
        associated_token::token_program = token_program,
    )]
    pub sender_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        init_if_needed,
        payer = sender,
        associated_token::mint = mint,
        associated_token::authority = recipient,
        associated_token::token_program = token_program,
    )]
    pub recipient_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        init_if_needed,
        payer = sender,
        associated_token::mint = mint,
        associated_token::authority = treasury,
        associated_token::token_program = token_program,
    )]
    pub treasury_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(mut)]
    pub sender: Signer<'info>,

    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<PaySplDirect>,
    _phone_e164: String,
    amount: u64,
) -> Result<()> {
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
    {
        let cpi = CpiContext::new(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.sender_ata.to_account_info(),
                mint: ctx.accounts.mint.to_account_info(),
                to: ctx.accounts.recipient_ata.to_account_info(),
                authority: ctx.accounts.sender.to_account_info(),
            },
        );
        token_interface::transfer_checked(cpi, amount, ctx.accounts.mint.decimals)?;
    }

    // Transfer additive fee to treasury (skipped if rounded to 0).
    if fee > 0 {
        let cpi = CpiContext::new(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.sender_ata.to_account_info(),
                mint: ctx.accounts.mint.to_account_info(),
                to: ctx.accounts.treasury_ata.to_account_info(),
                authority: ctx.accounts.sender.to_account_info(),
            },
        );
        token_interface::transfer_checked(cpi, fee, ctx.accounts.mint.decimals)?;
    }

    emit!(PaymentEvent {
        sender: ctx.accounts.sender.key(),
        recipient: ctx.accounts.recipient.key(),
        phone_record: ctx.accounts.phone_record.key(),
        mint: ctx.accounts.mint.key(),
        amount,
        fee,
        ts: Clock::get()?.unix_timestamp,
    });

    Ok(())
}
