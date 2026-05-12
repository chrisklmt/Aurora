package gr.hua.aurora.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScaffold(
    title: String,
    messages: List<MessageListItem>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    localUsername: String? = null,
    topBarUsername: String? = null,
    topBarRightAction: AuroraTopBarAction = AuroraTopBarAction.NONE,
    onTopBarRightAction: (() -> Unit)? = null,
    composerHint: String = "Type a message",
    bodyTop: (@Composable ColumnScope.() -> Unit)? = null
) {
    // Το scaffold οργανώνει μόνο επαναχρησιμοποιήσιμες περιοχές chat UI χωρίς γνώση για state ή transport layers.
    var composerValue by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            AuroraTopBar(
                title = title,
                subtitle = subtitle,
                username = topBarUsername,
                rightAction = topBarRightAction,
                onRightActionClick = onTopBarRightAction
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            bodyTop?.invoke(this)

            if (bodyTop != null) {
                Spacer(Modifier.height(12.dp))
            }

            MessageList(
                messages = messages,
                localUsername = localUsername,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            ChatComposer(
                value = composerValue,
                onValueChange = { composerValue = it },
                onSend = { typedText ->
                    onSend(typedText)
                    composerValue = ""
                },
                hint = composerHint
            )
        }
    }
}
