use anchor_lang::prelude::*;

use crate::state::FaucetConfig;

#[derive(Accounts)]
pub struct SetCooldown<'info> {
    #[account(
        mut,
        seeds = [b"faucet_config"],
        bump = faucet_config.bump,
        has_one = admin,
    )]
    pub faucet_config: Account<'info, FaucetConfig>,
    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetCooldown>, cooldown_seconds: i64) -> Result<()> {
    require!(cooldown_seconds >= 0, crate::error::ErrorCode::CooldownNotElapsed);
    ctx.accounts.faucet_config.cooldown_seconds = cooldown_seconds;
    Ok(())
}
