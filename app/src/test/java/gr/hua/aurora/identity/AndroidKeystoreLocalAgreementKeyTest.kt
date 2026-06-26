package gr.hua.aurora.identity

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidKeystoreLocalAgreementKeyTest {
    @Test
    fun clearLocalIdentityEntriesDeletesOnlyConfiguredAliases() {
        val deletedAliases = mutableListOf<String>()

        val result = AndroidKeystoreLocalAgreementKey.clearLocalIdentityEntries(
            identity = LocalKeyIdentity.default(),
            hasAlias = { alias ->
                alias == LocalKeyIdentity.DEFAULT_SIGNING_ALIAS ||
                    alias == LocalKeyIdentity.DEFAULT_KEY_AGREEMENT_ALIAS
            },
            deleteAlias = deletedAliases::add
        )

        assertEquals(
            listOf(
                LocalKeyIdentity.DEFAULT_SIGNING_ALIAS,
                LocalKeyIdentity.DEFAULT_KEY_AGREEMENT_ALIAS
            ),
            deletedAliases
        )
        assertEquals(
            setOf(
                LocalKeyIdentity.DEFAULT_SIGNING_ALIAS,
                LocalKeyIdentity.DEFAULT_KEY_AGREEMENT_ALIAS
            ),
            result.clearedAliases
        )
    }
}
