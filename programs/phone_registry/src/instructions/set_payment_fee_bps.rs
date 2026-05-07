use anchor_lang::prelude::*;

use crate::state::{ConfigChanged, ConfigField, RegistryConfig};

#[derive(Accounts)]
pub struct SetPaymentFeeBps<'info> {
    #[account(
        mut,
        seeds = [b"config"],
        bump = config.bump,
        has_one = admin,
    )]
    pub config: Account<'info, RegistryConfig>,

    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<SetPaymentFeeBps>, payment_fee_bps: u16) -> Result<()> {
    ctx.accounts.config.payment_fee_bps = payment_fee_bps;
    emit!(ConfigChanged {
        admin: ctx.accounts.admin.key(),
        field: ConfigField::PaymentFeeBps,
        ts: Clock::get()?.unix_timestamp,
    });
    Ok(())
}
