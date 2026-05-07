use anchor_lang::prelude::*;

#[error_code]
pub enum ErrorCode {
    #[msg("Output amount below the configured slippage minimum")]
    SlippageExceeded,
    #[msg("Price denominator must be non-zero")]
    InvalidPrice,
    #[msg("Input amount must be greater than zero")]
    AmountIsZero,
    #[msg("Reserve has insufficient output liquidity")]
    InsufficientReserve,
}
