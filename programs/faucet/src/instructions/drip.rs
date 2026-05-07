use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token_interface::{self, Mint, MintTo, TokenAccount, TokenInterface};

use crate::error::ErrorCode;
use crate::state::{DripCooldown, FaucetConfig, MintInfo};

#[derive(Accounts)]
pub struct Drip<'info> {
    #[account(
        seeds = [b"faucet_config"],
        bump = faucet_config.bump,
    )]
    pub faucet_config: Account<'info, FaucetConfig>,

    #[account(
        seeds = [b"mint_info", mint.key().as_ref()],
        bump = mint_info.bump,
        constraint = mint_info.mint == mint.key() @ ErrorCode::MintNotRegistered,
    )]
    pub mint_info: Account<'info, MintInfo>,

    #[account(mut)]
    pub mint: InterfaceAccount<'info, Mint>,

    /// CHECK: PDA mint authority signer.
    #[account(seeds = [b"mint_auth"], bump = faucet_config.mint_authority_bump)]
    pub mint_authority: UncheckedAccount<'info>,

    #[account(
        init_if_needed,
        payer = recipient,
        associated_token::mint = mint,
        associated_token::authority = recipient,
        associated_token::token_program = token_program,
    )]
    pub recipient_ata: InterfaceAccount<'info, TokenAccount>,

    #[account(
        init_if_needed,
        payer = recipient,
        space = 8 + DripCooldown::INIT_SPACE,
        seeds = [b"cooldown", mint.key().as_ref(), recipient.key().as_ref()],
        bump,
    )]
    pub cooldown: Account<'info, DripCooldown>,

    #[account(mut)]
    pub recipient: Signer<'info>,

    pub token_program: Interface<'info, TokenInterface>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<Drip>) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let cooldown = &mut ctx.accounts.cooldown;
    let cfg = &ctx.accounts.faucet_config;

    if cooldown.last_drip_ts != 0 {
        require!(
            now - cooldown.last_drip_ts >= cfg.cooldown_seconds,
            ErrorCode::CooldownNotElapsed
        );
    }

    let drip_amount = ctx.accounts.mint_info.drip_amount;
    let mint_auth_bump = cfg.mint_authority_bump;
    let seeds: &[&[u8]] = &[b"mint_auth", std::slice::from_ref(&mint_auth_bump)];
    let signer_seeds = &[seeds];

    let cpi_accounts = MintTo {
        mint: ctx.accounts.mint.to_account_info(),
        to: ctx.accounts.recipient_ata.to_account_info(),
        authority: ctx.accounts.mint_authority.to_account_info(),
    };
    let cpi_ctx = CpiContext::new_with_signer(
        ctx.accounts.token_program.key(),
        cpi_accounts,
        signer_seeds,
    );
    token_interface::mint_to(cpi_ctx, drip_amount)?;

    cooldown.bump = ctx.bumps.cooldown;
    cooldown.last_drip_ts = now;

    Ok(())
}
