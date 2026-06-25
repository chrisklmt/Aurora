package gr.hua.aurora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.navigation.compose.rememberNavController
import gr.hua.aurora.data.LocalProfileStore
import gr.hua.aurora.navigation.NavGraph
import gr.hua.aurora.state.AuroraStateViewModel
import gr.hua.aurora.state.rememberAuroraBleRuntimeState
import gr.hua.aurora.ui.theme.AuroraTheme

class MainActivity : ComponentActivity() {
    private val localProfileStore by lazy {
        LocalProfileStore(this)
    }

    private val auroraStateViewModel: AuroraStateViewModel by viewModels {
        AuroraStateViewModel.factory(localProfileStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuroraTheme {
                val navController = rememberNavController()
                val stateHolder = auroraStateViewModel.stateHolder
                val bleRuntimeState = rememberAuroraBleRuntimeState(
                    desiredAvailability = stateHolder.uiState.desiredAvailability,
                    stateHolder = stateHolder
                )
                NavGraph(
                    navController = navController,
                    stateHolder = stateHolder,
                    bleRuntimeState = bleRuntimeState
                )
            }
        }
    }
}
