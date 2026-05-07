use anchor_lang::prelude::*;

pub const PHONE_E164_MAX: usize = 16;
pub const ACCEPTED_MINTS_MAX: usize = 8;

/// Bytes reserved for forward-compat fields. Adding a field later means
/// repurposing this padding — existing accounts already have it allocated
/// and zero-initialized, so they read the new field as zero/default until
/// a migration writes a real value.
pub const RESERVED_PADDING: usize = 128;

/// PhoneRecord-side reserved padding minus the new attestation fields.
/// 32 (hash) + 1 (kind) + 8 (timestamp) = 41 bytes consumed; 87 left.
pub const PHONE_RECORD_RESERVED: usize = 87;

/// Attestation kind discriminator stored on PhoneRecord. 0 = none (the
/// record predates mandatory attestation); 1 = Android Keystore key
/// attestation (the standard path on stock Android); 2 = Seeker Seed
/// Vault hardware attestation (when available); 3 = web-delegated
/// (registrant proved phone ownership via a phone-side handoff before
/// signing the on-chain tx with a web wallet).
pub mod attestation_kind {
    pub const NONE: u8 = 0;
    pub const ANDROID_KEYSTORE: u8 = 1;
    pub const SEEKER_SEED_VAULT: u8 = 2;
    pub const WEB_DELEGATED: u8 = 3;
}

#[account]
#[derive(InitSpace)]
pub struct RegistryConfig {
    pub bump: u8,
    pub admin: Pubkey,
    pub treasury: Pubkey,
    pub registration_fee_sol_lamports: u64,
    pub registration_fee_spl_amount: u64,
    pub fee_spl_mint: Pubkey,             // SystemProgram::ID until configured
    pub payment_fee_bps: u16,             // 1 = 0.01%
    pub registration_lifetime: i64,       // 0 = no expiry in v1
    pub paused: bool,                     // emergency pause for all pay_* ix
    pub reserved: [u8; RESERVED_PADDING],
}

#[account]
#[derive(InitSpace)]
pub struct PhoneRecord {
    pub bump: u8,
    pub phone_e164: [u8; PHONE_E164_MAX],
    pub phone_len: u8,
    pub owner: Pubkey,
    pub accepted_mints: [Pubkey; ACCEPTED_MINTS_MAX],
    pub accepted_count: u8,
    pub preferred_receive: Pubkey,
    pub registered_at: i64,
    pub expires_at: i64,                  // 0 if no expiry
    pub flags: u32,

    /// sha256 of the canonical attestation blob (e.g. concatenated DER
    /// certs for Android Keystore key attestation). Off-chain verifiers
    /// re-hash the blob the registrant publishes elsewhere and compare.
    pub attestation_hash: [u8; 32],
    /// One of the [`attestation_kind`] constants.
    pub attestation_kind: u8,
    /// Unix seconds when the attestation was generated. Records older
    /// than a configurable freshness window may be re-attested.
    pub attested_at: i64,

    pub reserved: [u8; PHONE_RECORD_RESERVED],
}

#[account]
#[derive(InitSpace)]
pub struct ReverseIndex {
    pub bump: u8,
    pub owner: Pubkey,
    pub phone_record: Pubkey,
    pub reserved: [u8; 64],
}

pub fn validate_e164(phone: &str) -> Result<()> {
    let bytes = phone.as_bytes();
    require!(
        bytes.len() >= 2 && bytes.len() <= PHONE_E164_MAX,
        crate::error::ErrorCode::InvalidPhoneLength
    );
    require!(bytes[0] == b'+', crate::error::ErrorCode::InvalidPhoneFormat);
    for &b in &bytes[1..] {
        require!(
            b.is_ascii_digit(),
            crate::error::ErrorCode::InvalidPhoneFormat
        );
    }
    Ok(())
}

/// Emitted from every admin setter so off-chain auditors can detect
/// config changes without diffing state snapshots.
#[event]
pub struct ConfigChanged {
    pub admin: Pubkey,
    pub field: ConfigField,
    pub ts: i64,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, InitSpace, Debug, PartialEq, Eq)]
pub enum ConfigField {
    Admin,
    Treasury,
    RegistrationFeeSolLamports,
    RegistrationLifetime,
    SplFeeConfig,
    PaymentFeeBps,
    Paused,
}
