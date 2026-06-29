package gr.hua.aurora.wifidirect

import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectBroadcastReceiverTest {
    @Test
    fun broadcastEventParsesWifiP2pStateChangeSafely() {
        assertEquals(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION,
                isWifiP2pEnabled = true,
                isDiscoveryActive = null
            ),
            wifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION,
                wifiP2pState = WifiP2pManager.WIFI_P2P_STATE_ENABLED
            )
        )
    }

    @Test
    fun broadcastEventParsesDiscoveryStateSafely() {
        assertEquals(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION,
                isWifiP2pEnabled = null,
                isDiscoveryActive = false,
                isConnectionEstablished = null
            ),
            wifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION,
                discoveryState = WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
            )
        )
    }

    @Test
    fun broadcastEventParsesConnectionChangeSafely() {
        assertEquals(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION,
                isWifiP2pEnabled = null,
                isDiscoveryActive = null,
                isConnectionEstablished = true
            ),
            wifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION,
                isConnectionEstablished = true
            )
        )
    }

    @Test
    fun callbackBehaviorIsTestableWithoutAndroidIntentConstruction() {
        val receivedEvents = mutableListOf<WifiDirectBroadcastEvent>()
        val event = wifiDirectBroadcastEvent(
            action = WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION
        )

        receivedEvents += event

        assertEquals(
            listOf(
                WifiDirectBroadcastEvent(
                    action = WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION,
                    isConnectionEstablished = null
                )
            ),
            receivedEvents
        )
    }

    @Test
    fun statusActionsContainExpectedWifiDirectActions() {
        val actions = wifiDirectStatusActions()

        assertTrue(actions.contains(WifiManager.WIFI_STATE_CHANGED_ACTION))
        assertTrue(actions.contains(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION))
        assertTrue(actions.contains(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))
        assertTrue(actions.contains(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION))
        assertTrue(actions.contains(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
    }
}
