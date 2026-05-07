use anchor_lang::prelude::*;

#[error_code]
pub enum ErrorCode {
    #[msg("Drip cooldown has not elapsed yet")]
    CooldownNotElapsed,
    #[msg("Mint is not registered with the faucet")]
    MintNotRegistered,
}
