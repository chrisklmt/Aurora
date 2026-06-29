package gr.hua.aurora.wifidirect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class RememberedWifiDirectRuntimeStatusState(
    val status: WifiDirectRuntimeStatus,
    val refresh: () -> Unit,
    val refreshConnectionInfo: () -> Unit,
    val startDiscovery: () -> Unit,
    val stopDiscovery: () -> Unit,
    val connectToPeer: (WifiDirectPeer) -> Unit,
    val disconnect: () -> Unit
)

@Composable
fun rememberWifiDirectRuntimeStatusState(
    controller: WifiDirectController? = null
): RememberedWifiDirectRuntimeStatusState {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolvedController = remember(appContext, controller) {
        controller ?: AndroidWifiDirectController(appContext)
    }
    var runtimeStatus by remember(resolvedController) {
        mutableStateOf(resolvedController.currentRuntimeStatus())
    }
    val refresh = remember(resolvedController) {
        {
            resolvedController.refreshRuntimeStatus()
        }
    }
    val startDiscovery = remember(resolvedController) {
        {
            resolvedController.startDiscovery()
        }
    }
    val stopDiscovery = remember(resolvedController) {
        {
            resolvedController.stopDiscovery()
        }
    }
    val refreshConnectionInfo = remember(resolvedController) {
        {
            resolvedController.refreshConnectionInfo()
        }
    }
    val connectToPeer = remember(resolvedController) {
        { peer: WifiDirectPeer ->
            resolvedController.connectToPeer(peer)
        }
    }
    val disconnect = remember(resolvedController) {
        {
            resolvedController.disconnect()
        }
    }

    DisposableEffect(resolvedController) {
        val listener = object : WifiDirectController.Listener {
            override fun onRuntimeStatusChanged(status: WifiDirectRuntimeStatus) {
                runtimeStatus = status
            }
        }

        resolvedController.addListener(listener)
        onDispose {
            resolvedController.removeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner, refresh, stopDiscovery, runtimeStatus.discoveryState) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> refresh()
                Lifecycle.Event.ON_STOP -> {
                    if (runtimeStatus.discoveryState == WifiDirectDiscoveryState.ACTIVE) {
                        stopDiscovery()
                    }
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(appContext, refresh) {
        val receiver = WifiDirectRuntimeBroadcastReceiver { event ->
            resolvedController.handleBroadcast(event)
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            wifiDirectStatusIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            runCatching {
                appContext.unregisterReceiver(receiver)
            }
        }
    }

    return RememberedWifiDirectRuntimeStatusState(
        status = runtimeStatus,
        refresh = refresh,
        refreshConnectionInfo = refreshConnectionInfo,
        startDiscovery = startDiscovery,
        stopDiscovery = stopDiscovery,
        connectToPeer = connectToPeer,
        disconnect = disconnect
    )
}
