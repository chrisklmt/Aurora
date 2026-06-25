package gr.hua.aurora.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import gr.hua.aurora.data.LocalProfileSettingsStore

class AuroraStateViewModel(
    localProfileStore: LocalProfileSettingsStore
) : ViewModel() {
    val stateHolder: AuroraStateHolder = createAuroraStateHolder(localProfileStore)

    companion object {
        fun factory(
            localProfileStore: LocalProfileSettingsStore
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AuroraStateViewModel::class.java)) {
                        return AuroraStateViewModel(localProfileStore) as T
                    }
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
