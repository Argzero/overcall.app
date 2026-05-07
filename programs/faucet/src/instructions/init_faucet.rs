use anchor_lang::prelude::*;

use crate::state::FaucetConfig;

#[derive(Accounts)]
pub struct InitFaucet<'info> {
    #[account(
        init,
        payer = admin,
        space = 8 + FaucetConfig::INIT_SPACE,
        seeds = [b"faucet_config"],
        bump,
    )]
    pub faucet_config: Account<'info, FaucetConfig>,

    /// CHECK: PDA used as mint authority for all faucet-controlled mints. Read-only.
    #[account(seeds = [b"mint_auth"], bump)]
    pub mint_authority: UncheckedAccount<'info>,

    #[account(mut)]
    pub admin: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<InitFaucet>, cooldown_seconds: i64) -> Result<()> {
    let cfg = &mut ctx.accounts.faucet_config;
    cfg.bump = ctx.bumps.faucet_config;
    cfg.admin = ctx.accounts.admin.key();
    cfg.mint_authority_bump = ctx.bumps.mint_authority;
    cfg.cooldown_seconds = cooldown_seconds;
    cfg.reserved = [0u8; 64];
    Ok(())
}
