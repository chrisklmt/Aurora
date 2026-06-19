package gr.hua.aurora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import gr.hua.aurora.data.LocalProfileStore
import gr.hua.aurora.navigation.NavGraph
import gr.hua.aurora.state.rememberAuroraBleRuntimeState
import gr.hua.aurora.state.rememberAuroraStateHolder
import gr.hua.aurora.ui.theme.AuroraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val localProfileStore = LocalProfileStore(this)
        setContent {
            AuroraTheme {
                val navController = rememberNavController()
                val stateHolder = rememberAuroraStateHolder(localProfileStore)
                val bleRuntimeState = rememberAuroraBleRuntimeState(
                    desiredAvailability = stateHolder.uiState.desiredAvailability
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
