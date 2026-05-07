package com.overcall.invite

import com.solana.publickey.SolanaPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    private val key = SolanaPublicKey.from("6SWskncXVVNQ4ubLFnMCS3jwd7BTJ3sy5BRaFoFJRNKR")

    @Test fun forInviter_round_trips_through_parser() {
        val link = InviteLink.forInviter(key)
        assertEquals("overcall://invite/6SWskncXVVNQ4ubLFnMCS3jwd7BTJ3sy5BRaFoFJRNKR", link)
        val parsed = InviteLink.parseInviter(link)
        assertEquals(key.base58(), parsed?.base58())
    }

    @Test fun parseInviter_rejects_wrong_scheme() {
        assertNull(InviteLink.parseInviter("https://overcall.app/invite/${key.base58()}"))
    }

    @Test fun parseInviter_rejects_empty_path() {
        assertNull(InviteLink.parseInviter("overcall://invite/"))
    }

    @Test fun parseInviter_rejects_garbage_pubkey() {
        assertNull(InviteLink.parseInviter("overcall://invite/not-a-pubkey"))
    }
}
