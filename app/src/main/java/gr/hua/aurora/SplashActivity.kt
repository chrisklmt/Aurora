package gr.hua.aurora

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatusReader
import gr.hua.aurora.ble.permissions.bluetoothReadinessIntentFilter
import gr.hua.aurora.ui.theme.AuroraTheme

internal enum class SplashGate {
    Loading,
    NeedsPermissions,
    NeedsBluetooth,
    NeedsLocation,
    Ready
}

class SplashActivity : ComponentActivity() {
    private var splashGate by mutableStateOf(SplashGate.Loading)
    private var hasNavigatedToMain = false
    private var isReadinessReceiverRegistered = false
    private val readinessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            evaluateStartupGate(autoRequestPermissions = false)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (wereAllRequestedPermissionsGranted(results)) {
            evaluateStartupGate(autoRequestPermissions = false)
        } else {
            Toast.makeText(
                this,
                "Bluetooth and Location/GPS access is required to continue.",
                Toast.LENGTH_SHORT
            ).show()
            finishAffinity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AuroraTheme {
                SplashScreen(
                    appName = getString(R.string.app_name),
                    gate = splashGate,
                    onGrantPermissions = {
                        val needed = requiredRuntimePermissions(this)
                        if (needed.isNotEmpty()) {
                            requestPermissionLauncher.launch(needed.toTypedArray())
                        } else {
                            evaluateStartupGate(autoRequestPermissions = false)
                        }
                    },
                    onOpenBluetoothSettings = {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    },
                    onOpenLocationSettings = {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    onContinueAnyway = {
                        goToMainWithDelay(targetSplashGate = SplashGate.Loading)
                    }
                )
            }
        }

        evaluateStartupGate(autoRequestPermissions = true)
    }

    override fun onStart() {
        super.onStart()
        registerReadinessReceiverIfNeeded()
    }

    override fun onStop() {
        unregisterReadinessReceiverIfNeeded()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!hasNavigatedToMain) {
            evaluateStartupGate(autoRequestPermissions = false)
        }
    }

    private fun evaluateStartupGate(autoRequestPermissions: Boolean) {
        if (hasNavigatedToMain) return

        val bluetoothStatus = BluetoothPermissionStatusReader.read(this)
        val startupMissingPermissions = requiredRuntimePermissions(this)
        val gate = resolveSplashGate(
            bluetoothStatus = bluetoothStatus,
            hasMissingStartupPermissions = startupMissingPermissions.isNotEmpty()
        )
        splashGate = gate

        when (gate) {
            SplashGate.NeedsPermissions -> {
                if (autoRequestPermissions) {
                    requestPermissionLauncher.launch(
                        startupMissingPermissions.toTypedArray()
                    )
                }
            }

            SplashGate.Ready -> goToMainWithDelay()

            SplashGate.Loading,
            SplashGate.NeedsBluetooth,
            SplashGate.NeedsLocation -> Unit
        }
    }

    private fun goToMainWithDelay(
        minDurationMs: Long = 1200,
        targetSplashGate: SplashGate = SplashGate.Ready
    ) {
        if (hasNavigatedToMain) return
        hasNavigatedToMain = true
        splashGate = targetSplashGate
        window.decorView.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, minDurationMs)
    }

    private fun registerReadinessReceiverIfNeeded() {
        if (isReadinessReceiverRegistered) return

        ContextCompat.registerReceiver(
            applicationContext,
            readinessReceiver,
            bluetoothReadinessIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReadinessReceiverRegistered = true
    }

    private fun unregisterReadinessReceiverIfNeeded() {
        if (!isReadinessReceiverRegistered) return

        runCatching {
            applicationContext.unregisterReceiver(readinessReceiver)
        }
        isReadinessReceiverRegistered = false
    }
}

internal fun resolveSplashGate(
    bluetoothStatus: BluetoothPermissionStatus,
    hasMissingStartupPermissions: Boolean = bluetoothStatus.missingPermissions.isNotEmpty()
): SplashGate {
    return when {
        hasMissingStartupPermissions -> SplashGate.NeedsPermissions
        bluetoothStatus.isBluetoothEnabled == false -> SplashGate.NeedsBluetooth
        bluetoothStatus.isLocationEnabled == false -> SplashGate.NeedsLocation
        bluetoothStatus.isReadinessComplete -> SplashGate.Ready
        else -> SplashGate.Loading
    }
}

internal fun shouldOfferContinueAnyway(gate: SplashGate): Boolean {
    return gate == SplashGate.NeedsBluetooth || gate == SplashGate.NeedsLocation
}

internal fun wereAllRequestedPermissionsGranted(
    results: Map<String, Boolean>
): Boolean {
    return results.values.all { it }
}

private fun requiredRuntimePermissions(ctx: Context): List<String> {
    val missing = BluetoothPermissionStatusReader
        .requiredPermissionsForSdkInt(Build.VERSION.SDK_INT)
        .toMutableList()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS)
    }

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
        addIfMissing(missing, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    return missing.filterNot { ctx.hasPermission(it) }
}

private fun addIfMissing(list: MutableList<String>, permission: String) {
    if (!list.contains(permission)) list.add(permission)
}

private fun Context.hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        permission
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
private fun SplashScreen(
    appName: String,
    gate: SplashGate,
    onGrantPermissions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onContinueAnyway: () -> Unit
) {
    var visible by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    val title = when (gate) {
        SplashGate.Loading,
        SplashGate.Ready -> appName
        SplashGate.NeedsPermissions -> "Bluetooth and Location/GPS access required"
        SplashGate.NeedsBluetooth -> "Bluetooth is required"
        SplashGate.NeedsLocation -> "Location/GPS is required"
    }

    val message = when (gate) {
        SplashGate.Loading,
        SplashGate.Ready -> "Aurora is preparing nearby discovery."
        SplashGate.NeedsPermissions ->
            "Grant Bluetooth and Location/GPS access so Aurora can discover nearby peers reliably."
        SplashGate.NeedsBluetooth ->
            "Turn on Bluetooth before Aurora can discover and connect to nearby peers."
        SplashGate.NeedsLocation ->
            "Turn on Location/GPS before Aurora can scan and discover nearby peers reliably."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "App logo",
                    modifier = Modifier.size(96.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            when (gate) {
                SplashGate.Loading,
                SplashGate.Ready -> CircularProgressIndicator()

                SplashGate.NeedsPermissions -> {
                    Button(onClick = onGrantPermissions) {
                        Text("Grant Bluetooth and Location access")
                    }
                }

                SplashGate.NeedsBluetooth -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = onOpenBluetoothSettings) {
                            Text("Open Bluetooth settings")
                        }
                        if (shouldOfferContinueAnyway(gate)) {
                            OutlinedButton(onClick = onContinueAnyway) {
                                Text("Continue anyway")
                            }
                        }
                    }
                }

                SplashGate.NeedsLocation -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = onOpenLocationSettings) {
                            Text("Open Location settings")
                        }
                        if (shouldOfferContinueAnyway(gate)) {
                            OutlinedButton(onClick = onContinueAnyway) {
                                Text("Continue anyway")
                            }
                        }
                    }
                }
            }
        }
    }
}
