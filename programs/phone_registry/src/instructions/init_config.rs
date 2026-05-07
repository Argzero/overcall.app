use anchor_lang::prelude::*;

use crate::state::RegistryConfig;

#[derive(Accounts)]
pub struct InitConfig<'info> {
    #[account(
        init,
        payer = admin,
        space = 8 + RegistryConfig::INIT_SPACE,
        seeds = [b"config"],
        bump,
    )]
    pub config: Account<'info, RegistryConfig>,

    #[account(mut)]
    pub admin: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<InitConfig>,
    treasury: Pubkey,
    registration_fee_sol_lamports: u64,
    payment_fee_bps: u16,
) -> Result<()> {
    let config = &mut ctx.accounts.config;
    config.bump = ctx.bumps.config;
    config.admin = ctx.accounts.admin.key();
    config.treasury = treasury;
    config.registration_fee_sol_lamports = registration_fee_sol_lamports;
    config.registration_fee_spl_amount = 0;
    config.fee_spl_mint = Pubkey::default();
    config.payment_fee_bps = payment_fee_bps;
    config.registration_lifetime = 0;
    config.paused = false;
    config.reserved = [0u8; crate::state::RESERVED_PADDING];
    Ok(())
}
