package com.overcall.config

import com.solana.publickey.SolanaPublicKey

/**
 * Mirrors `config/devnet.json` at the repo root. Hand-maintained for now;
 * a Gradle task can generate this from the JSON if drift becomes a problem.
 */
object OverCallConfig {
    val PHONE_REGISTRY_PROGRAM = SolanaPublicKey.from("3NMgh3Urpb9opAeCWMGHtE1Tm5G4aFzTxQX4Wf4uqDNx")
    val FAUCET_PROGRAM         = SolanaPublicKey.from("B9BkxtYa9VVgzoTLsJjfAjAyvf4ZV9yMbjGQCg77HVKm")
    val MOCK_SWAP_PROGRAM      = SolanaPublicKey.from("8oVCvsywAcrx1LewLgPAJhfHyUHn2d7UREUdgXzWagH3")

    val REGISTRY_CONFIG_PDA    = SolanaPublicKey.from("D1cUSzrZcRtitWsWP59dHXN4aMDEkxAvNrWPhTFbh48p")
    val FAUCET_CONFIG_PDA      = SolanaPublicKey.from("5aSXZhaSzznoLxJkU7LDcJYzn9s8TGxgYGyhBHn6DNJv")
    val FAUCET_MINT_AUTHORITY  = SolanaPublicKey.from("Cq1tZ6Foi7owDAdAHiZaMcfz8MP4JoMJES5Yt8wmVPuq")
    val SWAP_ORACLE_PDA        = SolanaPublicKey.from("GuCXzty3pHmmLwtm9qbod5XyCD33RegGdNjnQXZZgno7")

    val DSKR  = SolanaPublicKey.from("6K5waTfHBfww76rkr6zwGqk52EhCiWBpAwNoxt4GUzyU")
    val DUSDC = SolanaPublicKey.from("2Mj7KWrVUYExnivQj1y7NtQA2FQWSvfrkYaAL83KGpeH")
    val DWSOL = SolanaPublicKey.from("ACR8rbY1XZdnpoxWekbMm3hw8SzANMYcrR31BPXcNH2d")

    /**
     * Treasury pubkey snapshot at deploy time. **Do not pass this directly
     * to instructions** — call `RegistryClient.fetchConfig().treasury` so
     * the live value is used. After `set_treasury` rotation the on-chain
     * `has_one = treasury` constraint rejects any tx still using this
     * stale value.
     */
    val DEPLOYER_TREASURY = SolanaPublicKey.from("6SWskncXVVNQ4ubLFnMCS3jwd7BTJ3sy5BRaFoFJRNKR")

    /** Default token preference set baked in for the dev/seeker flavors. */
    val DEFAULT_ACCEPTED  = listOf(DSKR, DUSDC, DWSOL)
    val DEFAULT_PREFERRED = listOf(DUSDC, DSKR, DWSOL)
}
