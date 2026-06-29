package gr.hua.aurora.wifidirect

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList

internal object WifiDirectPeerMapper {
    fun normalizePeer(
        peer: WifiDirectPeer
    ): WifiDirectPeer {
        return mapPeer(
            deviceName = peer.deviceName,
            deviceAddress = peer.deviceAddress
        )
    }

    fun mapPeer(
        deviceName: String?,
        deviceAddress: String?
    ): WifiDirectPeer {
        return WifiDirectPeer(
            deviceName = deviceName?.trim()?.takeIf { it.isNotEmpty() },
            deviceAddress = deviceAddress?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    fun mapDevice(
        device: WifiP2pDevice
    ): WifiDirectPeer {
        return mapPeer(
            deviceName = device.deviceName,
            deviceAddress = device.deviceAddress
        )
    }

    fun mapDevices(
        devices: Collection<WifiP2pDevice>
    ): List<WifiDirectPeer> {
        return normalizePeers(
            devices.map(::mapDevice)
        )
    }

    fun mapDeviceList(
        peerList: WifiP2pDeviceList
    ): List<WifiDirectPeer> {
        return mapDevices(peerList.deviceList)
    }

    fun normalizePeers(
        peers: List<WifiDirectPeer>
    ): List<WifiDirectPeer> {
        return peers
            .distinctBy { peer ->
                peer.deviceAddress?.uppercase() ?: peer.deviceName.orEmpty()
            }
            .sortedWith(
                compareBy(
                    WifiDirectPeer::deviceName,
                    WifiDirectPeer::deviceAddress
                )
            )
    }
}
