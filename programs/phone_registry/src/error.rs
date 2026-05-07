use anchor_lang::prelude::*;

#[error_code]
pub enum ErrorCode {
    #[msg("Phone number is empty or exceeds maximum length")]
    InvalidPhoneLength,
    #[msg("Phone number is not normalized E.164 (must start with '+' and contain digits only)")]
    InvalidPhoneFormat,
    #[msg("Stake amount is below the configured minimum")]
    StakeBelowMinimum,
    #[msg("Too many accepted mints; max is 8")]
    TooManyAcceptedMints,
    #[msg("Caller is not the record owner")]
    NotOwner,
    #[msg("Registration has expired")]
    RegistrationExpired,
    #[msg("Recipient account does not match the PhoneRecord owner")]
    RecipientMismatch,
    #[msg("Payment amount must be greater than zero")]
    AmountIsZero,
    #[msg("Provided fee mint does not match the configured fee SPL mint")]
    WrongFeeMint,
    #[msg("SPL fee mint has not been configured")]
    SplFeeNotConfigured,
    #[msg("Registry is paused — no new payments accepted")]
    RegistryPaused,
    #[msg("Registration lifetime must be non-negative")]
    InvalidLifetime,
    #[msg("Attestation kind is invalid or not supported")]
    InvalidAttestationKind,
    #[msg("Registration requires a non-zero hardware attestation hash")]
    AttestationRequired,
}
