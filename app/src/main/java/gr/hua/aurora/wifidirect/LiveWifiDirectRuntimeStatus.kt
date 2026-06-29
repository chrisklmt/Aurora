package gr.hua.aurora.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
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
    val refresh: () -> Unit
)

fun wifiDirectStatusIntentFilter(): IntentFilter {
    return IntentFilter().apply {
        addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
    }
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
            runtimeStatus = resolvedController.currentRuntimeStatus()
        }
    }

    DisposableEffect(lifecycleOwner, refresh) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
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
                refresh()
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
        refresh = refresh
    )
}
