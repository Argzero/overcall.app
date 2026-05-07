use anchor_lang::prelude::*;

pub mod error;
pub mod instructions;
pub mod state;

use instructions::*;

declare_id!("B9BkxtYa9VVgzoTLsJjfAjAyvf4ZV9yMbjGQCg77HVKm");

#[program]
pub mod faucet {
    use super::*;

    pub fn init_faucet(ctx: Context<InitFaucet>, cooldown_seconds: i64) -> Result<()> {
        instructions::init_faucet::handler(ctx, cooldown_seconds)
    }

    pub fn register_mint(ctx: Context<RegisterMint>, drip_amount: u64) -> Result<()> {
        instructions::register_mint::handler(ctx, drip_amount)
    }

    pub fn drip(ctx: Context<Drip>) -> Result<()> {
        instructions::drip::handler(ctx)
    }

    pub fn set_faucet_admin(ctx: Context<SetFaucetAdmin>, new_admin: Pubkey) -> Result<()> {
        instructions::set_admin::handler(ctx, new_admin)
    }

    pub fn set_cooldown(ctx: Context<SetCooldown>, cooldown_seconds: i64) -> Result<()> {
        instructions::set_cooldown::handler(ctx, cooldown_seconds)
    }

    pub fn update_mint_drip(ctx: Context<UpdateMintDrip>, drip_amount: u64) -> Result<()> {
        instructions::update_mint_drip::handler(ctx, drip_amount)
    }
}
