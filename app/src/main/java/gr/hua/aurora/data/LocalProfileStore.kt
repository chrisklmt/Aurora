package gr.hua.aurora.data

import android.content.Context

class LocalProfileStore(
    context: Context
) {
    // Αυτό το shell κρατά μόνο απλές τοπικές ρυθμίσεις προφίλ και δεν αποτελεί secure storage.
    private val sharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun loadUsername(): String? {
        return sharedPreferences.getString(KEY_USERNAME, null)
    }

    fun saveUsername(username: String) {
        sharedPreferences.edit()
            .putString(KEY_USERNAME, username)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "aurora_preferences"
        const val KEY_USERNAME = "username"
    }
}
