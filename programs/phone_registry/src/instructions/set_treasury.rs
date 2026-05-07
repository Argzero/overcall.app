use anchor_lang::prelude::*;

use crate::state::{ConfigChanged, ConfigField, RegistryConfig};

#[derive(Accounts)]
pub struct SetTreasury<'info> {
    #[account(
        mut,
        seeds = [b"config"],
        bump = config.bump,
        has_one = admin,
    )]
    pub config: Account<'info, RegistryConfig>,

    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetTreasury>, new_treasury: Pubkey) -> Result<()> {
    ctx.accounts.config.treasury = new_treasury;
    emit!(ConfigChanged {
        admin: ctx.accounts.admin.key(),
        field: ConfigField::Treasury,
        ts: Clock::get()?.unix_timestamp,
    });
    Ok(())
}
