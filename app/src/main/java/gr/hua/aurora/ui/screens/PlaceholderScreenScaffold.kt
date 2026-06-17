package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import gr.hua.aurora.ui.components.AuroraTopBar
import gr.hua.aurora.ui.components.AuroraTopBarAction
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaceholderScreenScaffold(
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    username: String? = null,
    onUsernameTripleTap: (() -> Unit)? = null,
    rightAction: AuroraTopBarAction = AuroraTopBarAction.NONE,
    onRightActionClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            AuroraTopBar(
                title = title,
                subtitle = subtitle,
                subtitleContent = subtitleContent,
                username = username,
                onUsernameTripleTap = onUsernameTripleTap,
                rightAction = rightAction,
                onRightActionClick = onRightActionClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Προσωρινό placeholder μέχρι να προστεθεί σταδιακά η πραγματική λογική UI/ViewModel.
            Text(
                text = "This screen is a temporary skeleton for the navigation baseline.",
                style = MaterialTheme.typography.bodyMedium
            )
            content()
        }
    }
}
