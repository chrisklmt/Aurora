package gr.hua.aurora.data

import android.content.Context

data class LocalProfileSettings(
    val generatedUsername: String?,
    val customUsername: String?,
    val useCustomUsernameInGlobalChat: Boolean
)

interface LocalProfileSettingsStore {
    fun loadProfileSettings(): LocalProfileSettings
    fun saveGeneratedUsername(username: String)
    fun saveCustomUsername(username: String?)
    fun saveUseCustomUsernameInGlobalChat(enabled: Boolean)
    fun clearProfile()
}

class LocalProfileStore(
    context: Context
) : LocalProfileSettingsStore {
    // Αυτό το shell κρατά μόνο απλές τοπικές ρυθμίσεις προφίλ και δεν αποτελεί secure storage.
    private val sharedPrefs = context.applicationContext.getSharedPreferences(
        storeName,
        Context.MODE_PRIVATE
    )

    override fun loadProfileSettings(): LocalProfileSettings {
        val generatedUsername = sharedPrefs.getString(keyGeneratedUsername, null).trimToNull()
        val customUsername = sharedPrefs.getString(keyCustomUsername, null).trimToNull()
        val useCustomUsernameInGlobalChat = if (sharedPrefs.contains(keyUseCustomUsernameInGlobalChat)) {
            sharedPrefs.getBoolean(keyUseCustomUsernameInGlobalChat, true)
        } else {
            true
        }

        if (generatedUsername != null || customUsername != null) {
            return LocalProfileSettings(
                generatedUsername = generatedUsername,
                customUsername = customUsername,
                useCustomUsernameInGlobalChat = useCustomUsernameInGlobalChat
            )
        }

        val legacyUsername = sharedPrefs.getString(keyLegacyUsername, null).trimToNull()
            ?: return LocalProfileSettings(
                generatedUsername = null,
                customUsername = null,
                useCustomUsernameInGlobalChat = true
            )

        return if (GeneratedUsername.matchesFormat(legacyUsername)) {
            saveGeneratedUsername(legacyUsername)
            sharedPrefs.edit().remove(keyLegacyUsername).apply()
            LocalProfileSettings(
                generatedUsername = legacyUsername,
                customUsername = null,
                useCustomUsernameInGlobalChat = useCustomUsernameInGlobalChat
            )
        } else {
            val freshGeneratedUsername = GeneratedUsername.create()
            sharedPrefs.edit()
                .remove(keyLegacyUsername)
                .putString(keyGeneratedUsername, freshGeneratedUsername)
                .putString(keyCustomUsername, legacyUsername)
                .apply()
            LocalProfileSettings(
                generatedUsername = freshGeneratedUsername,
                customUsername = legacyUsername,
                useCustomUsernameInGlobalChat = true
            )
        }
    }

    override fun saveGeneratedUsername(username: String) {
        sharedPrefs.edit()
            .putString(keyGeneratedUsername, username)
            .apply()
    }

    override fun saveCustomUsername(username: String?) {
        sharedPrefs.edit().apply {
            if (username == null) {
                remove(keyCustomUsername)
            } else {
                putString(keyCustomUsername, username)
            }
        }.apply()
    }

    override fun saveUseCustomUsernameInGlobalChat(enabled: Boolean) {
        sharedPrefs.edit()
            .putBoolean(keyUseCustomUsernameInGlobalChat, enabled)
            .apply()
    }

    override fun clearProfile() {
        sharedPrefs.edit()
            .remove(keyLegacyUsername)
            .remove(keyGeneratedUsername)
            .remove(keyCustomUsername)
            .remove(keyUseCustomUsernameInGlobalChat)
            .apply()
    }

    private fun String?.trimToNull(): String? {
        return this?.trim()?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val storeName = "aurora_local_store"
        const val keyLegacyUsername = "username"
        const val keyGeneratedUsername = "generated_username"
        const val keyCustomUsername = "custom_username"
        const val keyUseCustomUsernameInGlobalChat = "use_custom_username_in_global_chat"
    }
}
