package gr.hua.aurora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import gr.hua.aurora.data.LocalProfileStore
import gr.hua.aurora.data.persistence.FileAuroraPersistenceStore
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRunner
import gr.hua.aurora.diagnostics.automated.rememberAutomatedDiagnosticsRunnerBindings
import gr.hua.aurora.navigation.NavGraph
import gr.hua.aurora.state.AuroraStateViewModel
import gr.hua.aurora.state.rememberAuroraBleRuntimeState
import gr.hua.aurora.ui.theme.AuroraTheme
import gr.hua.aurora.wifidirect.socket.rememberWifiDirectSocketState

class MainActivity : ComponentActivity() {
    private val localProfileStore by lazy {
        LocalProfileStore(this)
    }

    private val auroraPersistenceStore by lazy {
        FileAuroraPersistenceStore(this)
    }

    private val auroraStateViewModel: AuroraStateViewModel by viewModels {
        AuroraStateViewModel.factory(
            localProfileStore = localProfileStore,
            persistenceStore = auroraPersistenceStore
        )
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
                val wifiDirectSocketState = rememberWifiDirectSocketState(
                    runtimeStatus = bleRuntimeState.wifiDirectRuntimeStatus,
                    processReceiveBridgeFrame = bleRuntimeState.receiveWifiDirectDebugTransportFrame
                )
                val automatedDiagnosticsBindings = rememberAutomatedDiagnosticsRunnerBindings(
                    stateHolder = stateHolder,
                    bleRuntimeState = bleRuntimeState,
                    wifiDirectSocketState = wifiDirectSocketState
                )
                val automatedDiagnosticsRunner = remember(automatedDiagnosticsBindings) {
                    AutomatedDiagnosticsRunner(automatedDiagnosticsBindings)
                }
                NavGraph(
                    navController = navController,
                    stateHolder = stateHolder,
                    bleRuntimeState = bleRuntimeState,
                    wifiDirectSocketState = wifiDirectSocketState,
                    automatedDiagnosticsRunner = automatedDiagnosticsRunner
                )
            }
        }
    }
}
