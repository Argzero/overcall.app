use anchor_lang::prelude::*;

use crate::state::OracleConfig;

#[derive(Accounts)]
pub struct SetSwapAdmin<'info> {
    #[account(
        mut,
        seeds = [b"oracle"],
        bump = oracle_config.bump,
        has_one = admin,
    )]
    pub oracle_config: Account<'info, OracleConfig>,
    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetSwapAdmin>, new_admin: Pubkey) -> Result<()> {
    ctx.accounts.oracle_config.admin = new_admin;
    Ok(())
}
