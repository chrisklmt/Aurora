package gr.hua.aurora

import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import gr.hua.aurora.ui.theme.AuroraTheme
import kotlinx.coroutines.delay

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AuroraTheme {
                SplashScreen(
                    appName=getString(R.string.app_name),
                    onFinished={
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                )

            }
        }
    }
}

@Composable
private fun SplashScreen(
    appName: String,
    onFinished: () -> Unit,
    minDurationMs: Long = 1200
){
    var visible by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        visible = true
        delay(minDurationMs)
        onFinished()
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