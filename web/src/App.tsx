import { useMemo } from "react";
import { ConnectionProvider, WalletProvider } from "@solana/wallet-adapter-react";
import { WalletModalProvider } from "@solana/wallet-adapter-react-ui";
import { PhantomWalletAdapter } from "@solana/wallet-adapter-wallets";
import { RegisterPage } from "./RegisterPage";
import { RPC_URL } from "./lib/config";

export function App() {
  // Mainnet wallets just speak the wallet-standard protocol; for devnet we
  // don't need anything special. Phantom + Backpack + Solflare register
  // themselves automatically via the Wallet Standard discovery.
  const wallets = useMemo(() => [new PhantomWalletAdapter()], []);

  return (
    <ConnectionProvider endpoint={RPC_URL}>
      <WalletProvider wallets={wallets} autoConnect>
        <WalletModalProvider>
          <RegisterPage />
        </WalletModalProvider>
      </WalletProvider>
    </ConnectionProvider>
  );
}
