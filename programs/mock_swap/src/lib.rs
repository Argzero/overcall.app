use anchor_lang::prelude::*;

pub mod error;
pub mod instructions;
pub mod state;

use instructions::*;

declare_id!("8oVCvsywAcrx1LewLgPAJhfHyUHn2d7UREUdgXzWagH3");

#[program]
pub mod mock_swap {
    use super::*;

    pub fn init_oracle(ctx: Context<InitOracle>) -> Result<()> {
        instructions::init_oracle::handler(ctx)
    }

    pub fn set_price(ctx: Context<SetPrice>, numerator: u64, denominator: u64) -> Result<()> {
        instructions::set_price::handler(ctx, numerator, denominator)
    }

    pub fn fund_reserve(ctx: Context<FundReserve>, amount: u64) -> Result<()> {
        instructions::fund_reserve::handler(ctx, amount)
    }

    pub fn swap_and_send(
        ctx: Context<SwapAndSend>,
        input_amount: u64,
        min_output: u64,
    ) -> Result<()> {
        instructions::swap_and_send::handler(ctx, input_amount, min_output)
    }

    pub fn set_swap_admin(ctx: Context<SetSwapAdmin>, new_admin: Pubkey) -> Result<()> {
        instructions::set_admin::handler(ctx, new_admin)
    }
}
