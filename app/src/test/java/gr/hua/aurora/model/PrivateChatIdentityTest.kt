package gr.hua.aurora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PrivateChatIdentityTest {
    @Test
    fun sharedChatIdIsDerivedSymmetricallyForBothPeers() {
        val aliceProposal = "0123456789abcdeffedcba9876543210"
        val bobProposal = "00112233445566778899aabbccddeeff"

        val aliceDerived = PrivateChatIdentity.deriveSharedChatId(
            localProposalId = aliceProposal,
            remoteProposalId = bobProposal
        )
        val bobDerived = PrivateChatIdentity.deriveSharedChatId(
            localProposalId = bobProposal,
            remoteProposalId = aliceProposal
        )

        assertEquals(aliceDerived, bobDerived)
        assertNotEquals(aliceProposal, aliceDerived)
        assertNotEquals(bobProposal, bobDerived)
    }
}
