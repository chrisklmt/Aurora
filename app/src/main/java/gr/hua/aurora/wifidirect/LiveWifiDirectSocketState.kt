package gr.hua.aurora.wifidirect

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class RememberedWifiDirectSocketState(
    val diagnostics: WifiDirectSocketDiagnostics,
    val startServer: (String?) -> Unit,
    val connectClient: (String) -> Unit,
    val sendPing: () -> Unit,
    val closeSocket: () -> Unit
)

@Composable
internal fun rememberWifiDirectSocketState(
    runtimeStatus: WifiDirectRuntimeStatus,
    controller: WifiDirectSocketController? = null
): RememberedWifiDirectSocketState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember {
        Handler(Looper.getMainLooper())
    }
    val resolvedController = remember(controller) {
        controller ?: AndroidWifiDirectSocketController()
    }
    var diagnostics by remember(resolvedController) {
        mutableStateOf(resolvedController.currentDiagnostics())
    }

    val startServer = remember(resolvedController) {
        { hostHint: String? ->
            resolvedController.startServer(hostHint)
        }
    }
    val connectClient = remember(resolvedController) {
        { host: String ->
            resolvedController.connectClient(host)
        }
    }
    val sendPing = remember(resolvedController) {
        {
            resolvedController.sendDebugPing()
        }
    }
    val closeSocket = remember(resolvedController) {
        {
            resolvedController.closeSocket()
        }
    }

    DisposableEffect(resolvedController, mainHandler) {
        val listener = object : WifiDirectSocketController.Listener {
            override fun onSocketDiagnosticsChanged(diagnosticsUpdate: WifiDirectSocketDiagnostics) {
                mainHandler.post {
                    diagnostics = diagnosticsUpdate
                }
            }
        }

        resolvedController.addListener(listener)
        onDispose {
            resolvedController.removeListener(listener)
            resolvedController.dispose()
        }
    }

    DisposableEffect(lifecycleOwner, closeSocket) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                closeSocket()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        runtimeStatus.connectionStatus.state,
        runtimeStatus.connectionStatus.groupFormed
    ) {
        if (
            runtimeStatus.connectionStatus.state != WifiDirectConnectionState.CONNECTED ||
            runtimeStatus.connectionStatus.groupFormed != WifiDirectGroupFormedState.YES
        ) {
            closeSocket()
        }
    }

    return RememberedWifiDirectSocketState(
        diagnostics = diagnostics,
        startServer = startServer,
        connectClient = connectClient,
        sendPing = sendPing,
        closeSocket = closeSocket
    )
}
