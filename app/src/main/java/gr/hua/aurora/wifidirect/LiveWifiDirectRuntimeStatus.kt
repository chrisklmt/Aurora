package gr.hua.aurora.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
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
    val startDiscovery: () -> Unit,
    val stopDiscovery: () -> Unit
)

fun wifiDirectStatusIntentFilter(): IntentFilter {
    return IntentFilter().apply {
        addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
    }
}

fun wifiDirectBroadcastEvent(
    intent: Intent?
): WifiDirectBroadcastEvent {
    val action = intent?.action
    val wifiP2pEnabled = if (action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
        when (
            intent.getIntExtra(
                WifiP2pManager.EXTRA_WIFI_STATE,
                -1
            )
        ) {
            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> true
            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> false
            else -> null
        }
    } else {
        null
    }
    val discoveryActive = if (action == WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION) {
        when (
            intent.getIntExtra(
                WifiP2pManager.EXTRA_DISCOVERY_STATE,
                -1
            )
        ) {
            WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> true
            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> false
            else -> null
        }
    } else {
        null
    }

    return WifiDirectBroadcastEvent(
        action = action,
        isWifiP2pEnabled = wifiP2pEnabled,
        isDiscoveryActive = discoveryActive
    )
}

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
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                resolvedController.handleBroadcast(wifiDirectBroadcastEvent(intent))
            }
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
        startDiscovery = startDiscovery,
        stopDiscovery = stopDiscovery
    )
}
