use anchor_lang::prelude::*;

pub mod error;
pub mod instructions;
pub mod state;

use instructions::*;

declare_id!("3NMgh3Urpb9opAeCWMGHtE1Tm5G4aFzTxQX4Wf4uqDNx");

#[program]
pub mod phone_registry {
    use super::*;

    pub fn init_config(
        ctx: Context<InitConfig>,
        treasury: Pubkey,
        registration_fee_sol_lamports: u64,
        payment_fee_bps: u16,
    ) -> Result<()> {
        instructions::init_config::handler(
            ctx,
            treasury,
            registration_fee_sol_lamports,
            payment_fee_bps,
        )
    }

    pub fn set_treasury(ctx: Context<SetTreasury>, new_treasury: Pubkey) -> Result<()> {
        instructions::set_treasury::handler(ctx, new_treasury)
    }

    pub fn set_admin(ctx: Context<SetAdmin>, new_admin: Pubkey) -> Result<()> {
        instructions::set_admin::handler(ctx, new_admin)
    }

    pub fn set_registration_fee_sol(
        ctx: Context<SetRegistrationFeeSol>,
        lamports: u64,
    ) -> Result<()> {
        instructions::set_registration_fee_sol::handler(ctx, lamports)
    }

    pub fn set_registration_lifetime(
        ctx: Context<SetRegistrationLifetime>,
        lifetime_seconds: i64,
    ) -> Result<()> {
        instructions::set_registration_lifetime::handler(ctx, lifetime_seconds)
    }

    pub fn set_paused(ctx: Context<SetPaused>, paused: bool) -> Result<()> {
        instructions::set_paused::handler(ctx, paused)
    }

    pub fn set_spl_fee_config(
        ctx: Context<SetSplFeeConfig>,
        fee_spl_mint: Pubkey,
        registration_fee_spl_amount: u64,
    ) -> Result<()> {
        instructions::set_spl_fee_config::handler(ctx, fee_spl_mint, registration_fee_spl_amount)
    }

    pub fn set_payment_fee_bps(
        ctx: Context<SetPaymentFeeBps>,
        payment_fee_bps: u16,
    ) -> Result<()> {
        instructions::set_payment_fee_bps::handler(ctx, payment_fee_bps)
    }

    pub fn register_with_sol_fee(
        ctx: Context<RegisterWithSolFee>,
        phone_e164: String,
        accepted_mints: Vec<Pubkey>,
        preferred_receive: Pubkey,
        flags: u32,
        attestation_hash: [u8; 32],
        attestation_kind: u8,
    ) -> Result<()> {
        instructions::register_with_sol_fee::handler(
            ctx,
            phone_e164,
            accepted_mints,
            preferred_receive,
            flags,
            attestation_hash,
            attestation_kind,
        )
    }

    pub fn register_with_spl_fee(
        ctx: Context<RegisterWithSplFee>,
        phone_e164: String,
        accepted_mints: Vec<Pubkey>,
        preferred_receive: Pubkey,
        flags: u32,
        attestation_hash: [u8; 32],
        attestation_kind: u8,
    ) -> Result<()> {
        instructions::register_with_spl_fee::handler(
            ctx,
            phone_e164,
            accepted_mints,
            preferred_receive,
            flags,
            attestation_hash,
            attestation_kind,
        )
    }

    pub fn update_preferences(
        ctx: Context<UpdatePreferences>,
        phone_e164: String,
        accepted_mints: Vec<Pubkey>,
        preferred_receive: Pubkey,
        flags: u32,
    ) -> Result<()> {
        instructions::update_preferences::handler(
            ctx,
            phone_e164,
            accepted_mints,
            preferred_receive,
            flags,
        )
    }

    pub fn rotate_owner(ctx: Context<RotateOwner>, phone_e164: String) -> Result<()> {
        instructions::rotate_owner::handler(ctx, phone_e164)
    }

    pub fn revoke(ctx: Context<Revoke>, phone_e164: String) -> Result<()> {
        instructions::revoke::handler(ctx, phone_e164)
    }

    pub fn pay_sol(ctx: Context<PaySol>, phone_e164: String, amount: u64) -> Result<()> {
        instructions::pay_sol::handler(ctx, phone_e164, amount)
    }

    pub fn pay_spl_direct(
        ctx: Context<PaySplDirect>,
        phone_e164: String,
        amount: u64,
    ) -> Result<()> {
        instructions::pay_spl_direct::handler(ctx, phone_e164, amount)
    }

    pub fn pay_spl_via_swap(
        ctx: Context<PaySplViaSwap>,
        phone_e164: String,
        input_amount: u64,
        min_output: u64,
    ) -> Result<()> {
        instructions::pay_spl_via_swap::handler(ctx, phone_e164, input_amount, min_output)
    }
}
