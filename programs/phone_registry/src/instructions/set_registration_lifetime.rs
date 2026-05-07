use anchor_lang::prelude::*;

use crate::error::ErrorCode;
use crate::state::{ConfigChanged, ConfigField, RegistryConfig};

#[derive(Accounts)]
pub struct SetRegistrationLifetime<'info> {
    #[account(
        mut,
        seeds = [b"config"],
        bump = config.bump,
        has_one = admin,
    )]
    pub config: Account<'info, RegistryConfig>,
    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetRegistrationLifetime>, lifetime_seconds: i64) -> Result<()> {
    require!(lifetime_seconds >= 0, ErrorCode::InvalidLifetime);
    ctx.accounts.config.registration_lifetime = lifetime_seconds;
    emit!(ConfigChanged {
        admin: ctx.accounts.admin.key(),
        field: ConfigField::RegistrationLifetime,
        ts: Clock::get()?.unix_timestamp,
    });
    Ok(())
}
