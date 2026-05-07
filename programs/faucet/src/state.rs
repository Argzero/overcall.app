use anchor_lang::prelude::*;

#[account]
#[derive(InitSpace)]
pub struct FaucetConfig {
    pub bump: u8,
    pub admin: Pubkey,
    pub mint_authority_bump: u8,    // bump for the [b"mint_auth"] PDA
    pub cooldown_seconds: i64,
    pub reserved: [u8; 64],
}

#[account]
#[derive(InitSpace)]
pub struct MintInfo {
    pub bump: u8,
    pub mint: Pubkey,
    pub drip_amount: u64,
    pub decimals: u8,
    pub reserved: [u8; 32],
}

#[account]
#[derive(InitSpace)]
pub struct DripCooldown {
    pub bump: u8,
    pub last_drip_ts: i64,
}
