use anchor_lang::prelude::*;

use crate::state::{ConfigChanged, ConfigField, RegistryConfig};

#[derive(Accounts)]
pub struct SetAdmin<'info> {
    #[account(
        mut,
        seeds = [b"config"],
        bump = config.bump,
        has_one = admin,
    )]
    pub config: Account<'info, RegistryConfig>,

    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetAdmin>, new_admin: Pubkey) -> Result<()> {
    let old_admin = ctx.accounts.admin.key();
    ctx.accounts.config.admin = new_admin;
    emit!(ConfigChanged {
        admin: old_admin,
        field: ConfigField::Admin,
        ts: Clock::get()?.unix_timestamp,
    });
    Ok(())
}
