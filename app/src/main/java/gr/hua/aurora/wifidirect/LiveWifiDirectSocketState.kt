package gr.hua.aurora.wifidirect

import android.os.Handler
import android.os.Looper
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
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
    val receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics,
    val startServer: (String?) -> Unit,
    val connectClient: (String) -> Unit,
    val sendFrame: () -> Unit,
    val sendAdapterFrame: () -> Unit,
    val setReceiveBridgeEnabled: (Boolean) -> Unit,
    val disableReceiveBridge: () -> Unit,
    val closeSocket: () -> Unit
)

@Composable
internal fun rememberWifiDirectSocketState(
    runtimeStatus: WifiDirectRuntimeStatus,
    processReceiveBridgeFrame: ((BleGattTransportFrame) -> BleTransportReceiveResult)? = null,
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
    val receiveBridge = remember(processReceiveBridgeFrame) {
        processReceiveBridgeFrame?.let(::WifiDirectReceiveBridge)
    }
    var diagnostics by remember(resolvedController) {
        mutableStateOf(resolvedController.currentDiagnostics())
    }
    var adapterDiagnostics by remember(transportAdapter) {
        mutableStateOf(transportAdapter.currentDiagnostics())
    }
    var receiveBridgeDiagnostics by remember(receiveBridge) {
        mutableStateOf(
            receiveBridge?.currentDiagnostics() ?: WifiDirectReceiveBridgeDiagnostics()
        )
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
    val setReceiveBridgeEnabled = remember(receiveBridge) {
        { enabled: Boolean ->
            receiveBridge?.setEnabled(enabled)
            if (receiveBridge == null && !enabled) {
                receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
            }
        }
    }
    val disableReceiveBridge = remember(receiveBridge, setReceiveBridgeEnabled) {
        {
            setReceiveBridgeEnabled(false)
        }
    }
    val closeSocket = remember(resolvedController) {
        {
            resolvedController.closeSocket()
        }
    }

    DisposableEffect(resolvedController, mainHandler, receiveBridge) {
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

            override fun onTransportFrameReceived(frame: WifiDirectTransportFrame) {
                mainHandler.post {
                    receiveBridge?.onTransportFrameReceived(frame)
                }
            }
        }
        val receiveBridgeListener = object : WifiDirectReceiveBridge.Listener {
            override fun onReceiveBridgeDiagnosticsChanged(
                diagnosticsUpdate: WifiDirectReceiveBridgeDiagnostics
            ) {
                mainHandler.post {
                    receiveBridgeDiagnostics = diagnosticsUpdate
                }
            }
        }

        resolvedController.addListener(listener)
        transportAdapter.addListener(adapterListener)
        receiveBridge?.addListener(receiveBridgeListener)
        onDispose {
            resolvedController.removeListener(listener)
            transportAdapter.removeListener(adapterListener)
            receiveBridge?.removeListener(receiveBridgeListener)
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
        receiveBridgeDiagnostics = receiveBridgeDiagnostics,
        startServer = startServer,
        connectClient = connectClient,
        sendFrame = sendFrame,
        sendAdapterFrame = sendAdapterFrame,
        setReceiveBridgeEnabled = setReceiveBridgeEnabled,
        disableReceiveBridge = disableReceiveBridge,
        closeSocket = closeSocket
    )
}
