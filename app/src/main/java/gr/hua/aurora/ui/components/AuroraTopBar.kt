package gr.hua.aurora.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class AuroraTopBarAction {
    NONE,
    BACK,
    MENU
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuroraTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    username: String? = null,
    onUsernameTripleTap: (() -> Unit)? = null,
    rightAction: AuroraTopBarAction = AuroraTopBarAction.NONE,
    onRightActionClick: (() -> Unit)? = null
) {
    val sideSlotWidth = 104.dp

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        TopAppBar(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 8.dp),
            title = {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(sideSlotWidth),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (username != null) {
                            UsernameChip(
                                username = username,
                                modifier = Modifier.fillMaxWidth(),
                                onTripleTap = onUsernameTripleTap
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = sideSlotWidth),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                        if (subtitleContent != null) {
                            subtitleContent()
                        } else if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(sideSlotWidth),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        when (rightAction) {
                            AuroraTopBarAction.NONE -> Unit
                            AuroraTopBarAction.BACK -> {
                                TextButton(onClick = { onRightActionClick?.invoke() }) {
                                    Text("Back")
                                }
                            }
                            AuroraTopBarAction.MENU -> {
                                IconButton(onClick = { onRightActionClick?.invoke() }) {
                                    Text("\u2630", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}
