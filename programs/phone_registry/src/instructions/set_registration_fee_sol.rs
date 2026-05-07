use anchor_lang::prelude::*;

use crate::state::{ConfigChanged, ConfigField, RegistryConfig};

#[derive(Accounts)]
pub struct SetRegistrationFeeSol<'info> {
    #[account(
        mut,
        seeds = [b"config"],
        bump = config.bump,
        has_one = admin,
    )]
    pub config: Account<'info, RegistryConfig>,
    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetRegistrationFeeSol>, lamports: u64) -> Result<()> {
    ctx.accounts.config.registration_fee_sol_lamports = lamports;
    emit!(ConfigChanged {
        admin: ctx.accounts.admin.key(),
        field: ConfigField::RegistrationFeeSolLamports,
        ts: Clock::get()?.unix_timestamp,
    });
    Ok(())
}
