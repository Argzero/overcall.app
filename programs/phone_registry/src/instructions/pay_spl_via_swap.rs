use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_interface::{self, Mint, TokenAccount, TokenInterface, TransferChecked};

use mock_swap::cpi::accounts::SwapAndSend as MockSwapAccounts;
use mock_swap::cpi::swap_and_send as cpi_swap_and_send;
use mock_swap::program::MockSwap;
use mock_swap::state::Price;

use crate::error::ErrorCode;
use crate::instructions::pay_sol::PaymentEvent;
use crate::state::{PhoneRecord, RegistryConfig};

/// Cross-mint payment. Sender holds `input_mint`; recipient wants
/// `output_mint`. Atomic flow:
///   1. Skim additive fee in input_mint to treasury_input_ata.
///   2. CPI to mock_swap::swap_and_send to convert `amount` of input_mint
///      and deliver the resulting output_mint balance directly to the
///      recipient's output_ata.
///
/// Polymorphic via token_interface (Token + Token-2022). The inner
/// mock_swap is also TokenInterface-based, so the CPI accounts struct
/// passes through cleanly.
#[derive(Accounts)]
#[instruction(phone_e164: String)]
pub struct PaySplViaSwap<'info> {
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

    pub input_mint: Box<InterfaceAccount<'info, Mint>>,
    pub output_mint: Box<InterfaceAccount<'info, Mint>>,

    /// CHECK: Verified in handler against phone_record.owner.
    pub recipient: UncheckedAccount<'info>,

    /// CHECK: Verified via has_one = treasury on config.
    pub treasury: UncheckedAccount<'info>,

    #[account(
        init_if_needed,
        payer = sender,
        associated_token::mint = input_mint,
        associated_token::authority = treasury,
        associated_token::token_program = token_program,
    )]
    pub treasury_input_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        mut,
        associated_token::mint = input_mint,
        associated_token::authority = sender,
        associated_token::token_program = token_program,
    )]
    pub sender_input_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    // ---- mock_swap accounts ------------------------------------------------
    pub swap_program: Program<'info, MockSwap>,

    pub price: Box<Account<'info, Price>>,

    /// CHECK: PDA owning the in-reserve ATA (verified by mock_swap).
    pub in_reserve_authority: UncheckedAccount<'info>,
    /// CHECK: PDA owning the out-reserve ATA (verified by mock_swap).
    pub out_reserve_authority: UncheckedAccount<'info>,

    #[account(
        mut,
        associated_token::mint = input_mint,
        associated_token::authority = in_reserve_authority,
        associated_token::token_program = token_program,
    )]
    pub in_reserve_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        mut,
        associated_token::mint = output_mint,
        associated_token::authority = out_reserve_authority,
        associated_token::token_program = token_program,
    )]
    pub out_reserve_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        init_if_needed,
        payer = sender,
        associated_token::mint = output_mint,
        associated_token::authority = recipient,
        associated_token::token_program = token_program,
    )]
    pub recipient_output_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    // ---- common ------------------------------------------------------------
    #[account(mut)]
    pub sender: Signer<'info>,

    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<PaySplViaSwap>,
    _phone_e164: String,
    input_amount: u64,
    min_output: u64,
) -> Result<()> {
    require!(!ctx.accounts.config.paused, ErrorCode::RegistryPaused);
    require!(input_amount > 0, ErrorCode::AmountIsZero);
    require_keys_eq!(
        ctx.accounts.phone_record.owner,
        ctx.accounts.recipient.key(),
        ErrorCode::RecipientMismatch
    );

    // 1. Additive fee in input_mint, paid to treasury.
    let bps = ctx.accounts.config.payment_fee_bps as u128;
    let fee = ((input_amount as u128) * bps / 10_000u128) as u64;
    if fee > 0 {
        let cpi = CpiContext::new(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.sender_input_ata.to_account_info(),
                mint: ctx.accounts.input_mint.to_account_info(),
                to: ctx.accounts.treasury_input_ata.to_account_info(),
                authority: ctx.accounts.sender.to_account_info(),
            },
        );
        token_interface::transfer_checked(cpi, fee, ctx.accounts.input_mint.decimals)?;
    }

    // 2. Compute expected output amount from the price oracle (so we can
    //    surface it to off-chain consumers via PaymentEvent).
    let output_amount = (input_amount as u128)
        .checked_mul(ctx.accounts.price.numerator as u128)
        .unwrap()
        .checked_div(ctx.accounts.price.denominator as u128)
        .unwrap() as u64;

    // 3. CPI to mock_swap::swap_and_send.
    let cpi_accounts = MockSwapAccounts {
        price: ctx.accounts.price.to_account_info(),
        in_mint: ctx.accounts.input_mint.to_account_info(),
        out_mint: ctx.accounts.output_mint.to_account_info(),
        in_reserve_authority: ctx.accounts.in_reserve_authority.to_account_info(),
        out_reserve_authority: ctx.accounts.out_reserve_authority.to_account_info(),
        in_reserve_ata: ctx.accounts.in_reserve_ata.to_account_info(),
        out_reserve_ata: ctx.accounts.out_reserve_ata.to_account_info(),
        sender_in_ata: ctx.accounts.sender_input_ata.to_account_info(),
        recipient_out_ata: ctx.accounts.recipient_output_ata.to_account_info(),
        recipient: ctx.accounts.recipient.to_account_info(),
        sender: ctx.accounts.sender.to_account_info(),
        token_program: ctx.accounts.token_program.to_account_info(),
        associated_token_program: ctx.accounts.associated_token_program.to_account_info(),
        system_program: ctx.accounts.system_program.to_account_info(),
    };
    let cpi_ctx = CpiContext::new(ctx.accounts.swap_program.key(), cpi_accounts);
    cpi_swap_and_send(cpi_ctx, input_amount, min_output)?;

    // 4. Emit PaymentEvent from the recipient's perspective.
    emit!(PaymentEvent {
        sender: ctx.accounts.sender.key(),
        recipient: ctx.accounts.recipient.key(),
        phone_record: ctx.accounts.phone_record.key(),
        mint: ctx.accounts.output_mint.key(),
        amount: output_amount,
        fee: 0, // fee was paid in input_mint; recipient doesn't see it
        ts: Clock::get()?.unix_timestamp,
    });

    Ok(())
}
