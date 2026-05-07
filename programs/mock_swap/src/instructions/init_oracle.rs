use anchor_lang::prelude::*;

use crate::state::OracleConfig;

#[derive(Accounts)]
pub struct InitOracle<'info> {
    #[account(
        init,
        payer = admin,
        space = 8 + OracleConfig::INIT_SPACE,
        seeds = [b"oracle"],
        bump,
    )]
    pub oracle_config: Account<'info, OracleConfig>,

    #[account(mut)]
    pub admin: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<InitOracle>) -> Result<()> {
    let cfg = &mut ctx.accounts.oracle_config;
    cfg.bump = ctx.bumps.oracle_config;
    cfg.admin = ctx.accounts.admin.key();
    cfg.reserved = [0u8; 64];
    Ok(())
}
