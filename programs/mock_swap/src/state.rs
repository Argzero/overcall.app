use anchor_lang::prelude::*;

#[account]
#[derive(InitSpace)]
pub struct OracleConfig {
    pub bump: u8,
    pub admin: Pubkey,
    pub reserved: [u8; 64],
}

/// Fixture price for a directed pair (in_mint -> out_mint).
/// `output = input * numerator / denominator`
#[account]
#[derive(InitSpace)]
pub struct Price {
    pub bump: u8,
    pub in_mint: Pubkey,
    pub out_mint: Pubkey,
    pub numerator: u64,
    pub denominator: u64,
    pub reserved: [u8; 32],
}
