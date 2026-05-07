use anchor_lang::prelude::*;
use anchor_spl::token_interface::Mint;

use crate::state::{FaucetConfig, MintInfo};

#[derive(Accounts)]
pub struct UpdateMintDrip<'info> {
    #[account(
        seeds = [b"faucet_config"],
        bump = faucet_config.bump,
        has_one = admin,
    )]
    pub faucet_config: Account<'info, FaucetConfig>,

    pub mint: InterfaceAccount<'info, Mint>,

    #[account(
        mut,
        seeds = [b"mint_info", mint.key().as_ref()],
        bump = mint_info.bump,
    )]
    pub mint_info: Account<'info, MintInfo>,

    pub admin: Signer<'info>,
}

pub fn handler(ctx: Context<UpdateMintDrip>, drip_amount: u64) -> Result<()> {
    ctx.accounts.mint_info.drip_amount = drip_amount;
    Ok(())
}
