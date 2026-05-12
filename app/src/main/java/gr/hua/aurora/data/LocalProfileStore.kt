package gr.hua.aurora.data

import android.content.Context

class LocalProfileStore(
    context: Context
) {
    // Αυτό το shell κρατά μόνο απλές τοπικές ρυθμίσεις προφίλ και δεν αποτελεί secure storage.
    private val sharedPrefs = context.applicationContext.getSharedPreferences(
        storeName,
        Context.MODE_PRIVATE
    )

    fun loadUsername(): String? {
        return sharedPrefs.getString(keyUsername, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun saveUsername(username: String) {
        sharedPrefs.edit()
            .putString(keyUsername, username)
            .apply()
    }

    fun clearProfile() {
        sharedPrefs.edit()
            .remove(keyUsername)
            .apply()
    }

    private companion object {
        const val storeName = "aurora_local_store"
        const val keyUsername = "username"
    }
}
