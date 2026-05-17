package gr.hua.aurora.data

import android.content.Context

data class LocalProfileSettings(
    val generatedUsername: String?,
    val customUsername: String?,
    val useCustomUsernameInGlobalChat: Boolean
)

internal data class StoredProfileSnapshot(
    val generatedUsername: String?,
    val customUsername: String?,
    val legacyUsername: String?,
    val useCustomUsernameInGlobalChat: Boolean?
)

internal data class ResolvedProfileSettings(
    val settings: LocalProfileSettings,
    val generatedUsernameToPersist: String? = null,
    val customUsernameToPersist: String? = null,
    val clearLegacyUsername: Boolean = false
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
        val resolvedProfileSettings = resolveStoredProfileSettings(
            snapshot = StoredProfileSnapshot(
                generatedUsername = sharedPrefs.getString(keyGeneratedUsername, null).trimToNull(),
                customUsername = sharedPrefs.getString(keyCustomUsername, null).trimToNull(),
                legacyUsername = sharedPrefs.getString(keyLegacyUsername, null).trimToNull(),
                useCustomUsernameInGlobalChat = sharedPrefs.takeIf {
                    it.contains(keyUseCustomUsernameInGlobalChat)
                }?.getBoolean(keyUseCustomUsernameInGlobalChat, true)
            ),
            createGeneratedUsername = GeneratedUsername::create
        )

        if (resolvedProfileSettings.generatedUsernameToPersist != null ||
            resolvedProfileSettings.customUsernameToPersist != null ||
            resolvedProfileSettings.clearLegacyUsername
        ) {
            sharedPrefs.edit().apply {
                if (resolvedProfileSettings.clearLegacyUsername) {
                    remove(keyLegacyUsername)
                }
                resolvedProfileSettings.generatedUsernameToPersist?.let {
                    putString(keyGeneratedUsername, it)
                }
                resolvedProfileSettings.customUsernameToPersist?.let {
                    putString(keyCustomUsername, it)
                }
            }.apply()
        }

        return resolvedProfileSettings.settings
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

internal fun resolveStoredProfileSettings(
    snapshot: StoredProfileSnapshot,
    createGeneratedUsername: () -> String
): ResolvedProfileSettings {
    val resolvedToggle = snapshot.useCustomUsernameInGlobalChat ?: true

    if (snapshot.generatedUsername != null || snapshot.customUsername != null) {
        return ResolvedProfileSettings(
            settings = LocalProfileSettings(
                generatedUsername = snapshot.generatedUsername,
                customUsername = snapshot.customUsername,
                useCustomUsernameInGlobalChat = resolvedToggle
            )
        )
    }

    val legacyUsername = snapshot.legacyUsername
        ?: return ResolvedProfileSettings(
            settings = LocalProfileSettings(
                generatedUsername = null,
                customUsername = null,
                useCustomUsernameInGlobalChat = true
            )
        )

    val freshGeneratedUsername = createGeneratedUsername()

    // Δεν μαντεύουμε αν το παλιό όνομα ήταν αυτόματο, γιατί ένα κανονικό όνομα χρήστη
    // μπορεί να έχει την ίδια μορφή και πρέπει να μείνει ορατό μόνο ως προσωπική επιλογή.
    return ResolvedProfileSettings(
        settings = LocalProfileSettings(
            generatedUsername = freshGeneratedUsername,
            customUsername = legacyUsername,
            useCustomUsernameInGlobalChat = resolvedToggle
        ),
        generatedUsernameToPersist = freshGeneratedUsername,
        customUsernameToPersist = legacyUsername,
        clearLegacyUsername = true
    )
}
