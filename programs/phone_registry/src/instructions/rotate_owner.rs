use anchor_lang::prelude::*;

use crate::state::{PhoneRecord, ReverseIndex};

#[derive(Accounts)]
#[instruction(phone_e164: String)]
pub struct RotateOwner<'info> {
    #[account(
        mut,
        seeds = [b"phone", phone_e164.as_bytes()],
        bump = phone_record.bump,
        has_one = owner,
    )]
    pub phone_record: Account<'info, PhoneRecord>,

    #[account(
        mut,
        close = owner,
        seeds = [b"by_owner", owner.key().as_ref()],
        bump = old_reverse.bump,
    )]
    pub old_reverse: Account<'info, ReverseIndex>,

    #[account(
        init,
        payer = owner,
        space = 8 + ReverseIndex::INIT_SPACE,
        seeds = [b"by_owner", new_owner.key().as_ref()],
        bump,
    )]
    pub new_reverse: Account<'info, ReverseIndex>,

    /// CHECK: Destination of ownership; doesn't need to sign at rotation time.
    pub new_owner: UncheckedAccount<'info>,

    #[account(mut)]
    pub owner: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<RotateOwner>, _phone_e164: String) -> Result<()> {
    let record = &mut ctx.accounts.phone_record;
    record.owner = ctx.accounts.new_owner.key();

    let new_reverse = &mut ctx.accounts.new_reverse;
    new_reverse.bump = ctx.bumps.new_reverse;
    new_reverse.owner = ctx.accounts.new_owner.key();
    new_reverse.phone_record = ctx.accounts.phone_record.key();
    new_reverse.reserved = [0u8; 64];
    Ok(())
}
