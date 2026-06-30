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
import gr.hua.aurora.model.OutgoingChatMessage

internal data class RememberedWifiDirectSocketState(
    val diagnostics: WifiDirectSocketDiagnostics,
    val adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    val sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    val globalDebugSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    val smokeTestDiagnostics: WifiDirectSmokeTestDiagnostics,
    val receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics,
    val startServer: (String?) -> Unit,
    val connectClient: (String) -> Unit,
    val sendFrame: () -> Unit,
    val sendAdapterFrame: () -> Unit,
    val sendBridgedFrame: () -> Unit,
    val sendGlobalDebugMessage: (OutgoingChatMessage, String) -> Unit,
    val sendSmokeTestFrame: (String) -> Unit,
    val setGlobalDebugSendEnabled: (Boolean) -> Unit,
    val disableGlobalDebugSend: () -> Unit,
    val setSendBridgeEnabled: (Boolean) -> Unit,
    val disableSendBridge: () -> Unit,
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
    val sendBridge = remember(transportAdapter) {
        WifiDirectSendBridge(transportAdapter)
    }
    val globalDebugSender = remember(sendBridge, transportAdapter) {
        WifiDirectGlobalDebugSendBridge(
            submitFrame = sendBridge::submit,
            sendBridgeDiagnostics = sendBridge::currentDiagnostics,
            transportAdapterDiagnostics = transportAdapter::currentDiagnostics
        )
    }
    val smokeTestSender = remember(sendBridge, transportAdapter) {
        WifiDirectSmokeTestSender(
            submitFrame = sendBridge::submit,
            sendBridgeDiagnostics = sendBridge::currentDiagnostics,
            transportAdapterDiagnostics = transportAdapter::currentDiagnostics
        )
    }
    var diagnostics by remember(resolvedController) {
        mutableStateOf(resolvedController.currentDiagnostics())
    }
    var adapterDiagnostics by remember(transportAdapter) {
        mutableStateOf(transportAdapter.currentDiagnostics())
    }
    var sendBridgeDiagnostics by remember(sendBridge) {
        mutableStateOf(sendBridge.currentDiagnostics())
    }
    var globalDebugSendDiagnostics by remember(globalDebugSender) {
        mutableStateOf(globalDebugSender.currentDiagnostics())
    }
    var smokeTestDiagnostics by remember(smokeTestSender) {
        mutableStateOf(smokeTestSender.currentDiagnostics())
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
    val sendBridgedFrame = remember(sendBridge) {
        {
            sendBridge.submit(wifiDirectSyntheticTransportFrame())
        }
    }
    val sendGlobalDebugMessage = remember(globalDebugSender) {
        { message: OutgoingChatMessage, senderId: String ->
            globalDebugSender.submitGlobalMessage(
                message = message,
                senderId = senderId
            )
        }
    }
    val sendSmokeTestFrame = remember(smokeTestSender) {
        { senderId: String ->
            smokeTestSender.sendPublicSmokeTest(senderId)
        }
    }
    val setGlobalDebugSendEnabled = remember(globalDebugSender) {
        { enabled: Boolean ->
            globalDebugSender.setEnabled(enabled)
        }
    }
    val disableGlobalDebugSend = remember(globalDebugSender, setGlobalDebugSendEnabled) {
        {
            setGlobalDebugSendEnabled(false)
        }
    }
    val setSendBridgeEnabled = remember(sendBridge) {
        { enabled: Boolean ->
            sendBridge.setEnabled(enabled)
        }
    }
    val disableSendBridge = remember(sendBridge) {
        {
            sendBridge.disable()
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

    DisposableEffect(
        resolvedController,
        mainHandler,
        sendBridge,
        globalDebugSender,
        smokeTestSender,
        receiveBridge
    ) {
        val listener = object : WifiDirectSocketController.Listener {
            override fun onSocketDiagnosticsChanged(diagnosticsUpdate: WifiDirectSocketDiagnostics) {
                mainHandler.post {
                    diagnostics = diagnosticsUpdate
                    adapterDiagnostics = transportAdapter.currentDiagnostics()
                    globalDebugSendDiagnostics = globalDebugSender.currentDiagnostics()
                    smokeTestDiagnostics = smokeTestSender.currentDiagnostics()
                }
            }
        }
        val adapterListener = object : WifiDirectTransportAdapter.Listener {
            override fun onTransportAdapterDiagnosticsChanged(
                diagnosticsUpdate: WifiDirectTransportAdapterDiagnostics
            ) {
                mainHandler.post {
                    adapterDiagnostics = diagnosticsUpdate
                    globalDebugSendDiagnostics = globalDebugSender.currentDiagnostics()
                    smokeTestDiagnostics = smokeTestSender.currentDiagnostics()
                }
            }

            override fun onTransportFrameReceived(frame: WifiDirectTransportFrame) {
                mainHandler.post {
                    receiveBridge?.onTransportFrameReceived(frame)
                }
            }
        }
        val sendBridgeListener = object : WifiDirectSendBridge.Listener {
            override fun onSendBridgeDiagnosticsChanged(
                diagnosticsUpdate: WifiDirectSendBridgeDiagnostics
            ) {
                mainHandler.post {
                    sendBridgeDiagnostics = diagnosticsUpdate
                    globalDebugSendDiagnostics = globalDebugSender.currentDiagnostics()
                    smokeTestDiagnostics = smokeTestSender.currentDiagnostics()
                }
            }
        }
        val globalDebugSendListener = object : WifiDirectGlobalDebugSendBridge.Listener {
            override fun onGlobalDebugSendDiagnosticsChanged(
                diagnosticsUpdate: WifiDirectGlobalDebugSendDiagnostics
            ) {
                mainHandler.post {
                    globalDebugSendDiagnostics = diagnosticsUpdate
                }
            }
        }
        val smokeTestListener = object : WifiDirectSmokeTestSender.Listener {
            override fun onSmokeTestDiagnosticsChanged(
                diagnosticsUpdate: WifiDirectSmokeTestDiagnostics
            ) {
                mainHandler.post {
                    smokeTestDiagnostics = diagnosticsUpdate
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
        sendBridge.addListener(sendBridgeListener)
        globalDebugSender.addListener(globalDebugSendListener)
        smokeTestSender.addListener(smokeTestListener)
        receiveBridge?.addListener(receiveBridgeListener)
        onDispose {
            resolvedController.removeListener(listener)
            transportAdapter.removeListener(adapterListener)
            sendBridge.removeListener(sendBridgeListener)
            globalDebugSender.removeListener(globalDebugSendListener)
            smokeTestSender.removeListener(smokeTestListener)
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
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        globalDebugSendDiagnostics = globalDebugSendDiagnostics,
        smokeTestDiagnostics = smokeTestDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics,
        startServer = startServer,
        connectClient = connectClient,
        sendFrame = sendFrame,
        sendAdapterFrame = sendAdapterFrame,
        sendBridgedFrame = sendBridgedFrame,
        sendGlobalDebugMessage = sendGlobalDebugMessage,
        sendSmokeTestFrame = sendSmokeTestFrame,
        setGlobalDebugSendEnabled = setGlobalDebugSendEnabled,
        disableGlobalDebugSend = disableGlobalDebugSend,
        setSendBridgeEnabled = setSendBridgeEnabled,
        disableSendBridge = disableSendBridge,
        setReceiveBridgeEnabled = setReceiveBridgeEnabled,
        disableReceiveBridge = disableReceiveBridge,
        closeSocket = closeSocket
    )
}
