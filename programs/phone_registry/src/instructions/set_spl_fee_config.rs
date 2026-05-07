use anchor_lang::prelude::*;

use crate::state::{ConfigChanged, ConfigField, RegistryConfig};

#[derive(Accounts)]
pub struct SetSplFeeConfig<'info> {
    #[account(
        mut,
        seeds = [b"config"],
        bump = config.bump,
        has_one = admin,
    )]
    pub config: Account<'info, RegistryConfig>,

    pub admin: Signer<'info>,
}

pub fn handler(
    ctx: Context<SetSplFeeConfig>,
    fee_spl_mint: Pubkey,
    registration_fee_spl_amount: u64,
) -> Result<()> {
    ctx.accounts.config.fee_spl_mint = fee_spl_mint;
    ctx.accounts.config.registration_fee_spl_amount = registration_fee_spl_amount;
    emit!(ConfigChanged {
        admin: ctx.accounts.admin.key(),
        field: ConfigField::SplFeeConfig,
        ts: Clock::get()?.unix_timestamp,
    });
    Ok(())
}
