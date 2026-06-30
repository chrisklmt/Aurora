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
    val adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    val startServer: (String?) -> Unit,
    val connectClient: (String) -> Unit,
    val sendFrame: () -> Unit,
    val sendAdapterFrame: () -> Unit,
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
    val transportAdapter = remember(resolvedController) {
        if (
            resolvedController is WifiDirectTransportFrameSink &&
            resolvedController is WifiDirectTransportFrameSource
        ) {
            WifiDirectTransportAdapter(
                frameSink = resolvedController,
                frameSource = resolvedController,
                enabled = true
            )
        } else {
            WifiDirectTransportAdapter(enabled = false)
        }
    }
    var diagnostics by remember(resolvedController) {
        mutableStateOf(resolvedController.currentDiagnostics())
    }
    var adapterDiagnostics by remember(transportAdapter) {
        mutableStateOf(transportAdapter.currentDiagnostics())
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
    val sendFrame = remember(resolvedController) {
        {
            resolvedController.sendDebugFrame()
        }
    }
    val sendAdapterFrame = remember(transportAdapter) {
        {
            transportAdapter.submit(wifiDirectSyntheticTransportFrame())
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
                    adapterDiagnostics = transportAdapter.currentDiagnostics()
                }
            }
        }
        val adapterListener = object : WifiDirectTransportAdapter.Listener {
            override fun onTransportAdapterDiagnosticsChanged(
                diagnosticsUpdate: WifiDirectTransportAdapterDiagnostics
            ) {
                mainHandler.post {
                    adapterDiagnostics = diagnosticsUpdate
                }
            }
        }

        resolvedController.addListener(listener)
        transportAdapter.addListener(adapterListener)
        onDispose {
            resolvedController.removeListener(listener)
            transportAdapter.removeListener(adapterListener)
            transportAdapter.dispose()
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
        adapterDiagnostics = adapterDiagnostics,
        startServer = startServer,
        connectClient = connectClient,
        sendFrame = sendFrame,
        sendAdapterFrame = sendAdapterFrame,
        closeSocket = closeSocket
    )
}
