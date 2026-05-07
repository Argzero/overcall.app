use anchor_lang::prelude::*;

use crate::state::{ConfigChanged, ConfigField, RegistryConfig};

#[derive(Accounts)]
pub struct SetPaused<'info> {
    #[account(
        mut,
        seeds = [b"config"],
        bump = config.bump,
        has_one = admin,
    )]
    pub config: Account<'info, RegistryConfig>,
    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetPaused>, paused: bool) -> Result<()> {
    ctx.accounts.config.paused = paused;
    emit!(ConfigChanged {
        admin: ctx.accounts.admin.key(),
        field: ConfigField::Paused,
        ts: Clock::get()?.unix_timestamp,
    });
    Ok(())
}
