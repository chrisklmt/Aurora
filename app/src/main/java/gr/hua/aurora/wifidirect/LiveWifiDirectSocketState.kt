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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.PreparedPrivateChatTransportFrame
import gr.hua.aurora.wifidirect.debug.WifiDirectGlobalDebugSendBridge
import gr.hua.aurora.wifidirect.debug.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendBridge
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridge
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridge
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSmokeTestDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSmokeTestSender

internal data class RememberedWifiDirectSocketState(
    val diagnostics: WifiDirectSocketDiagnostics,
    val adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    val sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    val globalDebugSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    val privateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    val smokeTestDiagnostics: WifiDirectSmokeTestDiagnostics,
    val receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics,
    val startServer: (String?) -> Unit,
    val connectClient: (String) -> Unit,
    val sendFrame: () -> Unit,
    val sendAdapterFrame: () -> Unit,
    val sendBridgedFrame: () -> Unit,
    val sendGlobalDebugMessage: (OutgoingChatMessage, String) -> Unit,
    val sendPrivateDebugMessage: (PreparedPrivateChatTransportFrame) -> Unit,
    val sendSmokeTestFrame: (String) -> Unit,
    val setGlobalDebugSendEnabled: (Boolean) -> Unit,
    val disableGlobalDebugSend: () -> Unit,
    val setPrivateDebugSendEnabled: (Boolean) -> Unit,
    val disablePrivateDebugSend: () -> Unit,
    val setSendBridgeEnabled: (Boolean) -> Unit,
    val disableSendBridge: () -> Unit,
    val setReceiveBridgeEnabled: (Boolean) -> Unit,
    val reportReceiveBridgeToggleBlocked: (String) -> Unit,
    val disableReceiveBridge: () -> Unit,
    val resetDiagnostics: () -> Unit,
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
    val latestProcessReceiveBridgeFrameState = rememberUpdatedState(processReceiveBridgeFrame)
    val receiveBridge = remember(processReceiveBridgeFrame != null) {
        if (processReceiveBridgeFrame == null) {
            null
        } else {
            WifiDirectReceiveBridge { frame ->
                checkNotNull(latestProcessReceiveBridgeFrameState.value) {
                    "Wi-Fi Direct receive bridge processor unavailable."
                }(frame)
            }
        }
    }
    val currentReceiveBridgeState = rememberUpdatedState(receiveBridge)
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
    val privateDebugSender = remember(sendBridge, transportAdapter) {
        WifiDirectPrivateDebugSendBridge(
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
    var privateDebugSendDiagnostics by remember(privateDebugSender) {
        mutableStateOf(privateDebugSender.currentDiagnostics())
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
    val sendPrivateDebugMessage = remember(privateDebugSender) {
        { preparedTransportFrame: PreparedPrivateChatTransportFrame ->
            privateDebugSender.submitPrivateMessage(preparedTransportFrame)
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
    val setPrivateDebugSendEnabled = remember(privateDebugSender) {
        { enabled: Boolean ->
            privateDebugSender.setEnabled(enabled)
        }
    }
    val disablePrivateDebugSend = remember(privateDebugSender, setPrivateDebugSendEnabled) {
        {
            setPrivateDebugSendEnabled(false)
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
            receiveBridgeDiagnostics = if (receiveBridge != null) {
                receiveBridge.currentDiagnostics()
            } else {
                receiveBridgeDiagnostics.copy(
                    enabled = false,
                    lastToggleAction = if (enabled) {
                        "Enable receive bridge"
                    } else {
                        "Disable receive bridge"
                    },
                    lastToggleResult = "blocked",
                    lastToggleBlockedReason = "Receive bridge unavailable."
                )
            }
        }
    }
    val reportReceiveBridgeToggleBlocked = remember(receiveBridge) {
        { blockedReason: String ->
            if (receiveBridge != null) {
                receiveBridge.recordBlockedToggle(
                    enabled = true,
                    reason = blockedReason
                )
                receiveBridgeDiagnostics = receiveBridge.currentDiagnostics()
            } else {
                receiveBridgeDiagnostics = receiveBridgeDiagnostics.copy(
                    lastToggleAction = "Enable receive bridge",
                    lastToggleResult = "blocked",
                    lastToggleBlockedReason = blockedReason
                )
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
    val resetDiagnostics = remember(
        resolvedController,
        transportAdapter,
        sendBridge,
        globalDebugSender,
        privateDebugSender,
        smokeTestSender,
        receiveBridge
    ) {
        {
            resolvedController.resetDiagnostics()
            transportAdapter.resetDiagnostics()
            sendBridge.resetDiagnostics()
            globalDebugSender.resetDiagnostics()
            privateDebugSender.resetDiagnostics()
            smokeTestSender.resetDiagnostics()
            receiveBridge?.resetDiagnostics()
                ?: run {
                    receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
                }
            diagnostics = resolvedController.currentDiagnostics()
            adapterDiagnostics = transportAdapter.currentDiagnostics()
            sendBridgeDiagnostics = sendBridge.currentDiagnostics()
            globalDebugSendDiagnostics = globalDebugSender.currentDiagnostics()
            privateDebugSendDiagnostics = privateDebugSender.currentDiagnostics()
            smokeTestDiagnostics = smokeTestSender.currentDiagnostics()
            receiveBridgeDiagnostics =
                receiveBridge?.currentDiagnostics() ?: WifiDirectReceiveBridgeDiagnostics()
        }
    }

    DisposableEffect(
        resolvedController,
        mainHandler,
        sendBridge,
        globalDebugSender,
        privateDebugSender,
        smokeTestSender
    ) {
        val listener = object : WifiDirectSocketController.Listener {
            override fun onSocketDiagnosticsChanged(diagnosticsUpdate: WifiDirectSocketDiagnostics) {
                mainHandler.post {
                    diagnostics = diagnosticsUpdate
                    adapterDiagnostics = transportAdapter.currentDiagnostics()
                    globalDebugSendDiagnostics = globalDebugSender.currentDiagnostics()
                    privateDebugSendDiagnostics = privateDebugSender.currentDiagnostics()
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
                    privateDebugSendDiagnostics = privateDebugSender.currentDiagnostics()
                    smokeTestDiagnostics = smokeTestSender.currentDiagnostics()
                }
            }

            override fun onTransportFrameReceived(frame: WifiDirectTransportFrame) {
                mainHandler.post {
                    currentReceiveBridgeState.value?.onTransportFrameReceived(frame)
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
                    privateDebugSendDiagnostics = privateDebugSender.currentDiagnostics()
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
        val privateDebugSendListener = object : WifiDirectPrivateDebugSendBridge.Listener {
            override fun onPrivateDebugSendDiagnosticsChanged(
                diagnosticsUpdate: WifiDirectPrivateDebugSendDiagnostics
            ) {
                mainHandler.post {
                    privateDebugSendDiagnostics = diagnosticsUpdate
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
        resolvedController.addListener(listener)
        transportAdapter.addListener(adapterListener)
        sendBridge.addListener(sendBridgeListener)
        globalDebugSender.addListener(globalDebugSendListener)
        privateDebugSender.addListener(privateDebugSendListener)
        smokeTestSender.addListener(smokeTestListener)
        onDispose {
            resolvedController.removeListener(listener)
            transportAdapter.removeListener(adapterListener)
            sendBridge.removeListener(sendBridgeListener)
            globalDebugSender.removeListener(globalDebugSendListener)
            privateDebugSender.removeListener(privateDebugSendListener)
            smokeTestSender.removeListener(smokeTestListener)
            transportAdapter.dispose()
            resolvedController.dispose()
        }
    }

    DisposableEffect(receiveBridge, mainHandler) {
        val receiveBridgeListener = object : WifiDirectReceiveBridge.Listener {
            override fun onReceiveBridgeDiagnosticsChanged(
                diagnosticsUpdate: WifiDirectReceiveBridgeDiagnostics
            ) {
                mainHandler.post {
                    receiveBridgeDiagnostics = diagnosticsUpdate
                }
            }
        }

        receiveBridge?.addListener(receiveBridgeListener)
        onDispose {
            receiveBridge?.removeListener(receiveBridgeListener)
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
        privateDebugSendDiagnostics = privateDebugSendDiagnostics,
        smokeTestDiagnostics = smokeTestDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics,
        startServer = startServer,
        connectClient = connectClient,
        sendFrame = sendFrame,
        sendAdapterFrame = sendAdapterFrame,
        sendBridgedFrame = sendBridgedFrame,
        sendGlobalDebugMessage = sendGlobalDebugMessage,
        sendPrivateDebugMessage = sendPrivateDebugMessage,
        sendSmokeTestFrame = sendSmokeTestFrame,
        setGlobalDebugSendEnabled = setGlobalDebugSendEnabled,
        disableGlobalDebugSend = disableGlobalDebugSend,
        setPrivateDebugSendEnabled = setPrivateDebugSendEnabled,
        disablePrivateDebugSend = disablePrivateDebugSend,
        setSendBridgeEnabled = setSendBridgeEnabled,
        disableSendBridge = disableSendBridge,
        setReceiveBridgeEnabled = setReceiveBridgeEnabled,
        reportReceiveBridgeToggleBlocked = reportReceiveBridgeToggleBlocked,
        disableReceiveBridge = disableReceiveBridge,
        resetDiagnostics = resetDiagnostics,
        closeSocket = closeSocket
    )
}
