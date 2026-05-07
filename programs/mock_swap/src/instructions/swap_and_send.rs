use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_interface::{self, Mint, TokenAccount, TokenInterface, TransferChecked};

use crate::error::ErrorCode;
use crate::state::Price;

/// Swap `input_amount` of `in_mint` from `sender` and deliver the converted
/// output to `recipient`'s ATA. Output reserves must already be funded.
///
/// Token types are polymorphic via `token_interface::*` — this works on
/// classic SPL Token and Token-2022 mints (including 2022 mints with
/// transfer-fee extensions, since we use TransferChecked).
///
/// Heavy accounts (Mints, TokenAccounts, Price) are boxed onto the heap
/// to stay within Solana's 4KB SBF stack limit.
#[derive(Accounts)]
pub struct SwapAndSend<'info> {
    pub price: Box<Account<'info, Price>>,

    pub in_mint: Box<InterfaceAccount<'info, Mint>>,
    pub out_mint: Box<InterfaceAccount<'info, Mint>>,

    /// CHECK: PDA owning the in-reserve ATA.
    #[account(seeds = [b"reserve_auth", in_mint.key().as_ref()], bump)]
    pub in_reserve_authority: UncheckedAccount<'info>,

    /// CHECK: PDA owning the out-reserve ATA.
    #[account(seeds = [b"reserve_auth", out_mint.key().as_ref()], bump)]
    pub out_reserve_authority: UncheckedAccount<'info>,

    #[account(
        init_if_needed,
        payer = sender,
        associated_token::mint = in_mint,
        associated_token::authority = in_reserve_authority,
        associated_token::token_program = token_program,
    )]
    pub in_reserve_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        mut,
        associated_token::mint = out_mint,
        associated_token::authority = out_reserve_authority,
        associated_token::token_program = token_program,
    )]
    pub out_reserve_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        mut,
        associated_token::mint = in_mint,
        associated_token::authority = sender,
        associated_token::token_program = token_program,
    )]
    pub sender_in_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    #[account(
        init_if_needed,
        payer = sender,
        associated_token::mint = out_mint,
        associated_token::authority = recipient,
        associated_token::token_program = token_program,
    )]
    pub recipient_out_ata: Box<InterfaceAccount<'info, TokenAccount>>,

    /// CHECK: Output destination wallet; ATA is derived under it.
    pub recipient: UncheckedAccount<'info>,

    #[account(mut)]
    pub sender: Signer<'info>,

    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<SwapAndSend>,
    input_amount: u64,
    min_output: u64,
) -> Result<()> {
    require!(input_amount > 0, ErrorCode::AmountIsZero);

    let output = (input_amount as u128)
        .checked_mul(ctx.accounts.price.numerator as u128)
        .unwrap()
        .checked_div(ctx.accounts.price.denominator as u128)
        .unwrap() as u64;
    require!(output >= min_output, ErrorCode::SlippageExceeded);
    require!(
        ctx.accounts.out_reserve_ata.amount >= output,
        ErrorCode::InsufficientReserve
    );

    // sender input ATA -> in reserve (sender authorizes)
    {
        let cpi = CpiContext::new(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.sender_in_ata.to_account_info(),
                mint: ctx.accounts.in_mint.to_account_info(),
                to: ctx.accounts.in_reserve_ata.to_account_info(),
                authority: ctx.accounts.sender.to_account_info(),
            },
        );
        token_interface::transfer_checked(cpi, input_amount, ctx.accounts.in_mint.decimals)?;
    }

    // out reserve -> recipient ATA (signed by reserve PDA seeds)
    {
        let out_mint_key = ctx.accounts.out_mint.key();
        let out_auth_bump = ctx.bumps.out_reserve_authority;
        let seeds: &[&[u8]] = &[
            b"reserve_auth",
            out_mint_key.as_ref(),
            std::slice::from_ref(&out_auth_bump),
        ];
        let signer = &[seeds];

        let cpi = CpiContext::new_with_signer(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.out_reserve_ata.to_account_info(),
                mint: ctx.accounts.out_mint.to_account_info(),
                to: ctx.accounts.recipient_out_ata.to_account_info(),
                authority: ctx.accounts.out_reserve_authority.to_account_info(),
            },
            signer,
        );
        token_interface::transfer_checked(cpi, output, ctx.accounts.out_mint.decimals)?;
    }

    emit!(SwapEvent {
        sender: ctx.accounts.sender.key(),
        recipient: ctx.accounts.recipient.key(),
        in_mint: ctx.accounts.in_mint.key(),
        out_mint: ctx.accounts.out_mint.key(),
        input_amount,
        output_amount: output,
        ts: Clock::get()?.unix_timestamp,
    });

    Ok(())
}

#[event]
pub struct SwapEvent {
    pub sender: Pubkey,
    pub recipient: Pubkey,
    pub in_mint: Pubkey,
    pub out_mint: Pubkey,
    pub input_amount: u64,
    pub output_amount: u64,
    pub ts: i64,
}
