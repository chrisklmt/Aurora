package gr.hua.aurora.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager

internal class WifiDirectRuntimeBroadcastReceiver(
    private val onEvent: (WifiDirectBroadcastEvent) -> Unit
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?
    ) {
        onEvent(
            wifiDirectBroadcastEvent(
                action = intent?.action,
                wifiP2pState = intent?.getIntExtra(
                    WifiP2pManager.EXTRA_WIFI_STATE,
                    -1
                ) ?: -1,
                discoveryState = intent?.getIntExtra(
                    WifiP2pManager.EXTRA_DISCOVERY_STATE,
                    -1
                ) ?: -1
            )
        )
    }
}

internal fun wifiDirectStatusActions(): List<String> {
    return listOf(
        WifiManager.WIFI_STATE_CHANGED_ACTION,
        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION,
        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION,
        WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION
    )
}

fun wifiDirectStatusIntentFilter(): IntentFilter {
    return IntentFilter().apply {
        wifiDirectStatusActions().forEach(::addAction)
    }
}

fun wifiDirectBroadcastEvent(
    intent: Intent?
): WifiDirectBroadcastEvent {
    return wifiDirectBroadcastEvent(
        action = intent?.action,
        wifiP2pState = intent?.getIntExtra(
            WifiP2pManager.EXTRA_WIFI_STATE,
            -1
        ) ?: -1,
        discoveryState = intent?.getIntExtra(
            WifiP2pManager.EXTRA_DISCOVERY_STATE,
            -1
        ) ?: -1
    )
}

internal fun wifiDirectBroadcastEvent(
    action: String?,
    wifiP2pState: Int = -1,
    discoveryState: Int = -1
): WifiDirectBroadcastEvent {
    val wifiP2pEnabled = if (action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
        when (wifiP2pState) {
            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> true
            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> false
            else -> null
        }
    } else {
        null
    }
    val discoveryActive = if (action == WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION) {
        when (discoveryState) {
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
