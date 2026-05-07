use anchor_lang::prelude::*;

use crate::state::FaucetConfig;

#[derive(Accounts)]
pub struct SetFaucetAdmin<'info> {
    #[account(
        mut,
        seeds = [b"faucet_config"],
        bump = faucet_config.bump,
        has_one = admin,
    )]
    pub faucet_config: Account<'info, FaucetConfig>,
    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetFaucetAdmin>, new_admin: Pubkey) -> Result<()> {
    ctx.accounts.faucet_config.admin = new_admin;
    Ok(())
}
