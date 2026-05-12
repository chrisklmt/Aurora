package gr.hua.aurora.ui.components

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun UsernameChip(
    username: String,
    modifier: Modifier = Modifier,
    onTripleTap: (() -> Unit)? = null
) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTapTimestamp by remember { mutableLongStateOf(0L) }
    val tripleTapWindowMs = 700L

    val clickModifier = if (onTripleTap != null) {
        Modifier.clickable {
            val now = SystemClock.elapsedRealtime()
            taps = if (now - lastTapTimestamp <= tripleTapWindowMs) taps + 1 else 1
            lastTapTimestamp = now
            if (taps >= 3) {
                taps = 0
                onTripleTap()
            }
        }
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .then(clickModifier),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = username,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
