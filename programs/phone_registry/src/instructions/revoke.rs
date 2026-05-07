use anchor_lang::prelude::*;

use crate::state::{PhoneRecord, ReverseIndex};

/// Closes the caller's PhoneRecord and ReverseIndex, refunding rent to the
/// owner. The original registration fee is NOT refunded — it was paid to
/// the treasury and is non-refundable by design.
#[derive(Accounts)]
#[instruction(phone_e164: String)]
pub struct Revoke<'info> {
    #[account(
        mut,
        close = owner,
        seeds = [b"phone", phone_e164.as_bytes()],
        bump = phone_record.bump,
        has_one = owner,
    )]
    pub phone_record: Account<'info, PhoneRecord>,

    #[account(
        mut,
        close = owner,
        seeds = [b"by_owner", owner.key().as_ref()],
        bump = reverse_index.bump,
    )]
    pub reverse_index: Account<'info, ReverseIndex>,

    #[account(mut)]
    pub owner: Signer<'info>,
}

pub fn handler(_ctx: Context<Revoke>, _phone_e164: String) -> Result<()> {
    Ok(())
}
