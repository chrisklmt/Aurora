package gr.hua.aurora.wifidirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectConnectionStateMapperTest {
    @Test
    fun groupOwnerClientAndUnknownRolesMapStably() {
        assertEquals(
            WifiDirectConnectionRole.GROUP_OWNER,
            wifiDirectConnectionSnapshot(
                groupFormed = true,
                isGroupOwner = true,
                groupOwnerAddress = "192.168.49.1"
            ).role
        )
        assertEquals(
            WifiDirectConnectionRole.CLIENT,
            wifiDirectConnectionSnapshot(
                groupFormed = true,
                isGroupOwner = false,
                groupOwnerAddress = "192.168.49.1"
            ).role
        )
        assertEquals(
            WifiDirectConnectionRole.UNKNOWN,
            wifiDirectConnectionSnapshot(
                groupFormed = null,
                isGroupOwner = null,
                groupOwnerAddress = null
            ).role
        )
    }

    @Test
    fun groupFormedYesNoAndUnknownMapStably() {
        assertEquals(
            WifiDirectGroupFormedState.YES,
            wifiDirectConnectionSnapshot(
                groupFormed = true,
                isGroupOwner = true,
                groupOwnerAddress = null
            ).groupFormed
        )
        assertEquals(
            WifiDirectGroupFormedState.NO,
            wifiDirectConnectionSnapshot(
                groupFormed = false,
                isGroupOwner = false,
                groupOwnerAddress = null
            ).groupFormed
        )
        assertEquals(
            WifiDirectGroupFormedState.UNKNOWN,
            wifiDirectConnectionSnapshot(
                groupFormed = null,
                isGroupOwner = null,
                groupOwnerAddress = null
            ).groupFormed
        )
    }

    @Test
    fun safeGroupOwnerAddressIsTrimmedOrDropped() {
        assertEquals(
            "192.168.49.1",
            wifiDirectConnectionSnapshot(
                groupFormed = true,
                isGroupOwner = true,
                groupOwnerAddress = " 192.168.49.1 "
            ).groupOwnerAddress
        )
        assertEquals(
            null,
            wifiDirectConnectionSnapshot(
                groupFormed = true,
                isGroupOwner = false,
                groupOwnerAddress = "   "
            ).groupOwnerAddress
        )
    }

    @Test
    fun connectionStatusFromSnapshotKeepsConnectingUntilGroupForms() {
        val status = wifiDirectConnectionStatusFromSnapshot(
            current = WifiDirectConnectionStatus(
                state = WifiDirectConnectionState.CONNECTING,
                targetPeer = WifiDirectPeer(
                    deviceName = "Aurora Alpha",
                    deviceAddress = "AA:BB:CC:DD:EE:01"
                )
            ),
            snapshot = WifiDirectConnectionSnapshot(
                groupFormed = WifiDirectGroupFormedState.NO,
                role = WifiDirectConnectionRole.UNKNOWN,
                groupOwnerAddress = null
            )
        )

        assertEquals(WifiDirectConnectionState.CONNECTING, status.state)
    }

    @Test
    fun peerMatchUsesAddressThenNameSafely() {
        assertTrue(
            wifiDirectPeerMatches(
                WifiDirectPeer(
                    deviceName = "Aurora Alpha",
                    deviceAddress = "aa:bb:cc:dd:ee:01"
                ),
                WifiDirectPeer(
                    deviceName = "Other Name",
                    deviceAddress = "AA:BB:CC:DD:EE:01"
                )
            )
        )
        assertTrue(
            wifiDirectPeerMatches(
                WifiDirectPeer(
                    deviceName = " Aurora Alpha ",
                    deviceAddress = null
                ),
                WifiDirectPeer(
                    deviceName = "aurora alpha",
                    deviceAddress = null
                )
            )
        )
        assertFalse(
            wifiDirectPeerMatches(
                WifiDirectPeer(
                    deviceName = "Aurora Alpha",
                    deviceAddress = null
                ),
                WifiDirectPeer(
                    deviceName = "Aurora Beta",
                    deviceAddress = null
                )
            )
        )
    }
}
