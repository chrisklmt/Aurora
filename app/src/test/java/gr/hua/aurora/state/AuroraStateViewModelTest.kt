package gr.hua.aurora.state

import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.data.persistence.InMemoryAuroraPersistenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AuroraStateViewModelTest {
    @Test
    fun viewModelRetainsStateHolderMessagesAcrossRepeatedAccess() {
        val viewModel = AuroraStateViewModel(
            localProfileStore = FakeProfileStore(),
            persistenceStore = InMemoryAuroraPersistenceStore()
        )

        val holder = viewModel.stateHolder
        holder.sendGlobalPreviewMessage("hello from retained state")

        assertSame(holder, viewModel.stateHolder)
        assertEquals(1, viewModel.stateHolder.uiState.globalMessages.size)
        assertEquals(
            "hello from retained state",
            viewModel.stateHolder.uiState.globalMessages.single().text
        )
    }

    private class FakeProfileStore : LocalProfileSettingsStore {
        override fun loadProfileSettings(): LocalProfileSettings {
            return LocalProfileSettings(
                generatedUsername = "PIAIUFN1",
                customUsername = null,
                useCustomUsernameInGlobalChat = true
            )
        }

        override fun saveGeneratedUsername(username: String) = Unit

        override fun saveCustomUsername(username: String?) = Unit

        override fun saveUseCustomUsernameInGlobalChat(enabled: Boolean) = Unit

        override fun clearProfile() = Unit
    }
}
