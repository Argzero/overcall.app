use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_interface::{self, Mint, TokenAccount, TokenInterface, TransferChecked};

#[derive(Accounts)]
pub struct FundReserve<'info> {
    pub mint: InterfaceAccount<'info, Mint>,

    /// CHECK: PDA that owns the reserve ATA for this mint.
    #[account(seeds = [b"reserve_auth", mint.key().as_ref()], bump)]
    pub reserve_authority: UncheckedAccount<'info>,

    #[account(
        init_if_needed,
        payer = funder,
        associated_token::mint = mint,
        associated_token::authority = reserve_authority,
        associated_token::token_program = token_program,
    )]
    pub reserve_ata: InterfaceAccount<'info, TokenAccount>,

    #[account(mut)]
    pub funder_ata: InterfaceAccount<'info, TokenAccount>,

    #[account(mut)]
    pub funder: Signer<'info>,

    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<FundReserve>, amount: u64) -> Result<()> {
    let cpi_accounts = TransferChecked {
        from: ctx.accounts.funder_ata.to_account_info(),
        mint: ctx.accounts.mint.to_account_info(),
        to: ctx.accounts.reserve_ata.to_account_info(),
        authority: ctx.accounts.funder.to_account_info(),
    };
    let cpi_ctx = CpiContext::new(ctx.accounts.token_program.key(), cpi_accounts);
    token_interface::transfer_checked(cpi_ctx, amount, ctx.accounts.mint.decimals)?;
    Ok(())
}
