package gr.hua.aurora

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import gr.hua.aurora.ui.theme.AuroraTheme


class SplashActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        var allGranted=results.values.all{it}
        if (allGranted) {
            goToMainWithDelay()
        }else{
            Toast.makeText(this, "Απαιτούνται δικαιώματα για να λειτουργήσει", Toast.LENGTH_SHORT).show()
            finishAffinity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AuroraTheme {
                SplashScreen(
                    appName=getString(R.string.app_name),
                )
            }
        }
        val needed=requiredRuntimePermissions(this)
        if (needed.isEmpty()) {
            goToMainWithDelay()
        }else{
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun goToMainWithDelay(minDurationMs: Long = 1200){
        window.decorView.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        },minDurationMs)
    }
}

private fun requiredRuntimePermissions(ctx:Context): List<String> {
    val missing = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        addIfMissing(missing, Manifest.permission.BLUETOOTH_SCAN)
        addIfMissing(missing, Manifest.permission.BLUETOOTH_CONNECT)
        addIfMissing(missing, Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS)
        }
    } else {
        addIfMissing(missing, Manifest.permission.ACCESS_COARSE_LOCATION)
        addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return missing.filterNot {ctx.hasPermission(it)
    }
}

private fun addIfMissing(list: MutableList<String>, permission: String){
    if (!list.contains(permission)) list.add(permission)
}

private fun Context.hasPermission(permission: String):Boolean{
    return ContextCompat.checkSelfPermission(
        this, permission
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
private fun SplashScreen(appName: String){
    var visible by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ){
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ){
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut()
            ){
                Image(
                    painter=painterResource(id=R.mipmap.ic_launcher_round),
                    contentDescription="App logo",
                    modifier=Modifier.size(96.dp)
                )
            }
            Text(
                text=appName,
                style=MaterialTheme.typography.headlineSmall,
                textAlign=TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator()
        }
    }
}