package gr.hua.aurora.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper

internal interface WifiDirectPlatformClient {
    fun discoverPeers(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun stopPeerDiscovery(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun requestPeers(
        onSuccess: (List<WifiDirectPeer>) -> Unit,
        onFailure: (String) -> Unit
    )
}

internal class AndroidWifiDirectPlatformClient private constructor(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : WifiDirectPlatformClient {

    override fun discoverPeers(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.discoverPeers(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun stopPeerDiscovery(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.stopPeerDiscovery(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun requestPeers(
        onSuccess: (List<WifiDirectPeer>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        runCatching {
            manager.requestPeers(channel) { peerList: WifiP2pDeviceList ->
                onSuccess(WifiDirectPeerMapper.mapDeviceList(peerList))
            }
        }.getOrElse { error ->
            onFailure(error::class.java.simpleName)
        }
    }

    companion object {
        fun create(context: Context): WifiDirectPlatformClient? {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return null
            val channel = runCatching {
                manager.initialize(appContext, Looper.getMainLooper(), null)
            }.getOrNull() ?: return null
            return AndroidWifiDirectPlatformClient(
                manager = manager,
                channel = channel
            )
        }
    }
}
