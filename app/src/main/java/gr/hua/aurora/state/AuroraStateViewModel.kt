package gr.hua.aurora.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.data.persistence.AuroraPersistenceStore

class AuroraStateViewModel(
    localProfileStore: LocalProfileSettingsStore,
    persistenceStore: AuroraPersistenceStore
) : ViewModel() {
    val stateHolder: AuroraStateHolder = createAuroraStateHolder(
        localProfileStore = localProfileStore,
        persistenceStore = persistenceStore
    )

    companion object {
        fun factory(
            localProfileStore: LocalProfileSettingsStore,
            persistenceStore: AuroraPersistenceStore
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AuroraStateViewModel::class.java)) {
                        return AuroraStateViewModel(
                            localProfileStore = localProfileStore,
                            persistenceStore = persistenceStore
                        ) as T
                    }
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
