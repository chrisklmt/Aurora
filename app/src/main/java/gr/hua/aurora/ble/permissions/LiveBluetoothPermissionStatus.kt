package gr.hua.aurora.ble.permissions

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.location.LocationManager
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

data class RememberedBluetoothPermissionStatusState(
    val status: BluetoothPermissionStatus,
    val refresh: () -> Unit
)

fun bluetoothReadinessIntentFilter(): IntentFilter {
    return IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        addAction(LocationManager.MODE_CHANGED_ACTION)
    }
}

@Composable
fun rememberBluetoothPermissionStatusState(): RememberedBluetoothPermissionStatusState {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    var bluetoothStatus by remember(appContext) {
        mutableStateOf(BluetoothPermissionStatusReader.read(appContext))
    }
    val refresh = remember(appContext) {
        {
            bluetoothStatus = BluetoothPermissionStatusReader.read(appContext)
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
            bluetoothReadinessIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            runCatching {
                appContext.unregisterReceiver(receiver)
            }
        }
    }

    return RememberedBluetoothPermissionStatusState(
        status = bluetoothStatus,
        refresh = refresh
    )
}
