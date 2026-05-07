use anchor_lang::prelude::*;

use crate::error::ErrorCode;
use crate::state::{PhoneRecord, ACCEPTED_MINTS_MAX};

#[derive(Accounts)]
#[instruction(phone_e164: String)]
pub struct UpdatePreferences<'info> {
    #[account(
        mut,
        seeds = [b"phone", phone_e164.as_bytes()],
        bump = phone_record.bump,
        has_one = owner,
    )]
    pub phone_record: Account<'info, PhoneRecord>,

    pub owner: Signer<'info>,
}

pub fn handler(
    ctx: Context<UpdatePreferences>,
    _phone_e164: String,
    accepted_mints: Vec<Pubkey>,
    preferred_receive: Pubkey,
    flags: u32,
) -> Result<()> {
    require!(
        accepted_mints.len() <= ACCEPTED_MINTS_MAX,
        ErrorCode::TooManyAcceptedMints
    );
    let record = &mut ctx.accounts.phone_record;
    record.accepted_mints = [Pubkey::default(); ACCEPTED_MINTS_MAX];
    for (i, mint) in accepted_mints.iter().enumerate() {
        record.accepted_mints[i] = *mint;
    }
    record.accepted_count = accepted_mints.len() as u8;
    record.preferred_receive = preferred_receive;
    record.flags = flags;
    Ok(())
}
