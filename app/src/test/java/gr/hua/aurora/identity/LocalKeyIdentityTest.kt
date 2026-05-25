package gr.hua.aurora.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalKeyIdentityTest {
    @Test
    fun defaultIdentityUsesExpectedAliases() {
        val identity = LocalKeyIdentity.default()

        assertEquals("aurora-local-signing", identity.signingAlias)
        assertEquals("aurora-local-agreement", identity.keyAgreementAlias)
    }

    @Test
    fun customAliasesAreTrimmed() {
        val identity = LocalKeyIdentity.create(
            signingAlias = "  custom-signing  ",
            keyAgreementAlias = "  custom-agreement  "
        )

        assertEquals("custom-signing", identity.signingAlias)
        assertEquals("custom-agreement", identity.keyAgreementAlias)
    }

    @Test
    fun blankSigningAliasFails() {
        try {
            LocalKeyIdentity.create(
                signingAlias = "   ",
                keyAgreementAlias = "custom-agreement"
            )
            fail("Creating an identity with a blank signing alias should fail.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun blankKeyAgreementAliasFails() {
        try {
            LocalKeyIdentity.create(
                signingAlias = "custom-signing",
                keyAgreementAlias = "   "
            )
            fail("Creating an identity with a blank key-agreement alias should fail.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun sameAliasForBothRolesFails() {
        try {
            LocalKeyIdentity.create(
                signingAlias = "same-alias",
                keyAgreementAlias = "  same-alias  "
            )
            fail("Creating an identity with duplicate role aliases should fail.")
        } catch (_: IllegalArgumentException) {
        }
    }
}
