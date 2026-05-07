#!/usr/bin/env bash
# Deploy the three Anchor programs to devnet and run the bootstrap script.
#
# Prerequisite: ~/.config/solana/id.json holds at least ~10 SOL on devnet.
# The public `solana airdrop` faucet is rate-limited; if it fails, request
# devnet SOL from https://faucet.solana.com/ (GitHub-authenticated) for
# the deployer pubkey printed below.
#
# Usage: ./scripts/deploy-devnet.sh

set -euo pipefail

export PATH="$HOME/.local/share/solana/install/active_release/bin:$HOME/.avm/bin:$HOME/.cargo/bin:$PATH"

DEPLOYER=$(solana address -k ~/.config/solana/id.json)
echo "Deployer: $DEPLOYER"

solana config set --url https://api.devnet.solana.com >/dev/null
echo "Cluster : $(solana config get | awk '/RPC URL/ {print $3}')"

BAL=$(solana balance | awk '{print $1}')
echo "Balance : $BAL SOL"
need_sol="10"
if (( $(echo "$BAL < $need_sol" | bc -l) )); then
  echo
  echo "ERROR: deployer needs >= $need_sol SOL on devnet."
  echo "  - Try:   solana airdrop 5"
  echo "  - Or visit https://faucet.solana.com/ (GitHub auth gives 5 SOL/req) and request to:"
  echo "      $DEPLOYER"
  exit 1
fi

echo
echo "anchor build ..."
anchor build

echo
echo "anchor deploy --provider.cluster devnet ..."
anchor deploy --provider.cluster devnet

echo
echo "Bootstrap (init_config / init_faucet / init_oracle / test mints / prices) ..."
ANCHOR_WALLET=~/.config/solana/id.json \
  ANCHOR_PROVIDER_URL=https://api.devnet.solana.com \
  pnpm exec tsx scripts/deploy-bootstrap.ts

echo
echo "Done. See config/devnet.json for the deployed addresses."
