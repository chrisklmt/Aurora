package gr.hua.aurora.state

import gr.hua.aurora.data.GeneratedUsername
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraStateHolderTest {
    @Test
    fun defaultToggleIsTrueInSampleState() {
        val state = SampleAuroraState.create(generatedUsername = "PIAIUFN1")

        assertTrue(state.useCustomUsernameInGlobalChat)
    }

    @Test
    fun updateUsernameStoresCustomWithoutChangingGeneratedUsername() {
        val store = FakeProfileStore()
        val holder = createHolder(
            store = store,
            generatedUsername = "PIAIUFN1"
        )

        holder.updateUsername(" John ")

        assertEquals("PIAIUFN1", holder.uiState.generatedUsername)
        assertEquals("John", holder.uiState.customUsername)
        assertEquals("John", holder.uiState.privateProfileUsername)
        assertEquals("John", store.customUsername)
    }

    @Test
    fun blankApplyIsIgnored() {
        val store = FakeProfileStore()
        val holder = createHolder(
            store = store,
            generatedUsername = "PIAIUFN1"
        )

        holder.updateUsername("   ")

        assertNull(holder.uiState.customUsername)
        assertNull(store.customUsername)
        assertEquals("PIAIUFN1", holder.uiState.generatedUsername)
    }

    @Test
    fun globalChatUsesCustomUsernameWhenToggleIsTrue() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1",
            customUsername = "John",
            useCustomUsernameInGlobalChat = true
        )

        holder.sendGlobalPreviewMessage("hello")

        assertEquals("John", holder.uiState.globalChatUsername)
        assertEquals("John", holder.uiState.globalMessages.last().senderName)
    }

    @Test
    fun globalChatUsesGeneratedUsernameWhenToggleIsFalse() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1",
            customUsername = "John",
            useCustomUsernameInGlobalChat = true
        )

        holder.updateUseCustomUsernameInGlobalChat(false)
        holder.sendGlobalPreviewMessage("hello")

        assertEquals("PIAIUFN1", holder.uiState.globalChatUsername)
        assertEquals("PIAIUFN1", holder.uiState.globalMessages.last().senderName)
    }

    @Test
    fun privateProfileUsesCustomUsernameWhenAvailable() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1",
            customUsername = "John"
        )

        holder.sendPrivatePreviewMessage("alex", "hello")

        assertEquals("John", holder.uiState.privateProfileUsername)
        assertEquals("John", holder.privateMessagesForPeerId("alex").last().senderName)
    }

    @Test
    fun resetCreatesFreshGeneratedUsernameClearsCustomAndResetsToggle() {
        val store = FakeProfileStore()
        val holder = createHolder(
            store = store,
            generatedUsername = "PIAIUFN1",
            customUsername = "John",
            useCustomUsernameInGlobalChat = false
        )

        holder.resetLocalData()

        assertTrue(GeneratedUsername.matchesFormat(holder.uiState.generatedUsername))
        assertNull(holder.uiState.customUsername)
        assertTrue(holder.uiState.useCustomUsernameInGlobalChat)
        assertEquals(holder.uiState.generatedUsername, store.generatedUsername)
        assertEquals(1, store.clearCalls)
    }

    private fun createHolder(
        store: FakeProfileStore,
        generatedUsername: String,
        customUsername: String? = null,
        useCustomUsernameInGlobalChat: Boolean = true
    ): AuroraStateHolder {
        return AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = generatedUsername,
                customUsername = customUsername,
                useCustomUsernameInGlobalChat = useCustomUsernameInGlobalChat
            ),
            localProfileStore = store
        )
    }

    private class FakeProfileStore : LocalProfileSettingsStore {
        var generatedUsername: String? = null
        var customUsername: String? = null
        var useCustomUsernameInGlobalChat: Boolean = true
        var clearCalls: Int = 0

        override fun loadProfileSettings(): LocalProfileSettings {
            return LocalProfileSettings(
                generatedUsername = generatedUsername,
                customUsername = customUsername,
                useCustomUsernameInGlobalChat = useCustomUsernameInGlobalChat
            )
        }

        override fun saveGeneratedUsername(username: String) {
            generatedUsername = username
        }

        override fun saveCustomUsername(username: String?) {
            customUsername = username
        }

        override fun saveUseCustomUsernameInGlobalChat(enabled: Boolean) {
            useCustomUsernameInGlobalChat = enabled
        }

        override fun clearProfile() {
            clearCalls += 1
            generatedUsername = null
            customUsername = null
            useCustomUsernameInGlobalChat = true
        }
    }
}
