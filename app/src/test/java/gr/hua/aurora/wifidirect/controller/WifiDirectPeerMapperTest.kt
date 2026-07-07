package gr.hua.aurora.wifidirect.controller

import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDirectPeerMapperTest {
    @Test
    fun mapsPeerNameAndAddressSafely() {
        assertEquals(
            WifiDirectPeer(
                deviceName = "Aurora Alpha",
                deviceAddress = "AA:BB:CC:DD:EE:01"
            ),
            WifiDirectPeerMapper.mapPeer(
                deviceName = "  Aurora Alpha  ",
                deviceAddress = " AA:BB:CC:DD:EE:01 "
            )
        )
    }

    @Test
    fun blankPeerNameBecomesNullSafely() {
        assertEquals(
            WifiDirectPeer(
                deviceName = null,
                deviceAddress = "AA:BB:CC:DD:EE:02"
            ),
            WifiDirectPeerMapper.mapPeer(
                deviceName = "   ",
                deviceAddress = "AA:BB:CC:DD:EE:02"
            )
        )
    }

    @Test
    fun emptyMappedPeersRemainEmpty() {
        assertEquals(
            emptyList<WifiDirectPeer>(),
            WifiDirectPeerMapper.normalizePeers(emptyList())
        )
    }

    @Test
    fun mappedPeersAreNormalizedDeterministically() {
        assertEquals(
            listOf(
                WifiDirectPeer(
                    deviceName = null,
                    deviceAddress = "AA:BB:CC:DD:EE:02"
                ),
                WifiDirectPeer(
                    deviceName = "Aurora Beta",
                    deviceAddress = "aa:bb:cc:dd:ee:03"
                )
            ),
            WifiDirectPeerMapper.normalizePeers(
                listOf(
                    WifiDirectPeerMapper.mapPeer(
                        deviceName = "Aurora Beta",
                        deviceAddress = "aa:bb:cc:dd:ee:03"
                    ),
                    WifiDirectPeerMapper.mapPeer(
                        deviceName = "Aurora Duplicate",
                        deviceAddress = "AA:BB:CC:DD:EE:03"
                    ),
                    WifiDirectPeerMapper.mapPeer(
                        deviceName = null,
                        deviceAddress = "AA:BB:CC:DD:EE:02"
                    )
                )
            )
        )
    }
}
