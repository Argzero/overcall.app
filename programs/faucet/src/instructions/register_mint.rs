use anchor_lang::prelude::*;
use anchor_spl::token_interface::Mint;

use crate::state::{FaucetConfig, MintInfo};

#[derive(Accounts)]
pub struct RegisterMint<'info> {
    #[account(
        seeds = [b"faucet_config"],
        bump = faucet_config.bump,
        has_one = admin,
    )]
    pub faucet_config: Account<'info, FaucetConfig>,

    pub mint: InterfaceAccount<'info, Mint>,

    #[account(
        init,
        payer = admin,
        space = 8 + MintInfo::INIT_SPACE,
        seeds = [b"mint_info", mint.key().as_ref()],
        bump,
    )]
    pub mint_info: Account<'info, MintInfo>,

    #[account(mut)]
    pub admin: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<RegisterMint>, drip_amount: u64) -> Result<()> {
    let info = &mut ctx.accounts.mint_info;
    info.bump = ctx.bumps.mint_info;
    info.mint = ctx.accounts.mint.key();
    info.drip_amount = drip_amount;
    info.decimals = ctx.accounts.mint.decimals;
    info.reserved = [0u8; 32];
    Ok(())
}
