use anchor_lang::prelude::*;
use anchor_spl::token::Mint;

use crate::error::ErrorCode;
use crate::state::{OracleConfig, Price};

#[derive(Accounts)]
pub struct SetPrice<'info> {
    #[account(
        seeds = [b"oracle"],
        bump = oracle_config.bump,
        has_one = admin,
    )]
    pub oracle_config: Account<'info, OracleConfig>,

    pub in_mint: Account<'info, Mint>,
    pub out_mint: Account<'info, Mint>,

    #[account(
        init_if_needed,
        payer = admin,
        space = 8 + Price::INIT_SPACE,
        seeds = [b"price", in_mint.key().as_ref(), out_mint.key().as_ref()],
        bump,
    )]
    pub price: Account<'info, Price>,

    #[account(mut)]
    pub admin: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<SetPrice>, numerator: u64, denominator: u64) -> Result<()> {
    require!(denominator > 0, ErrorCode::InvalidPrice);
    let p = &mut ctx.accounts.price;
    p.bump = ctx.bumps.price;
    p.in_mint = ctx.accounts.in_mint.key();
    p.out_mint = ctx.accounts.out_mint.key();
    p.numerator = numerator;
    p.denominator = denominator;
    p.reserved = [0u8; 32];
    Ok(())
}
