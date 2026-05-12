package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.navigation.Routes
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatScaffold
import gr.hua.aurora.ui.components.MessageListItem
import gr.hua.aurora.ui.components.TransportStatusCard
import gr.hua.aurora.ui.components.TransportStatusTone
import kotlinx.coroutines.launch

private val globalPreviewMessages = listOf(
    MessageListItem(
        sender = "Aurora",
        text = "The global chat layout is now rendered with reusable Compose components.",
        timestampLabel = "09:12"
    ),
    MessageListItem(
        sender = "You",
        text = "This screen is still a UI-only prototype with no real transport logic.",
        timestampLabel = "09:14"
    ),
    MessageListItem(
        sender = "Aurora",
        text = "Nearby, settings, and private chat routes remain available from this preview screen.",
        timestampLabel = "09:15",
        supportingLabel = "UI preview"
    )
)

@Composable
fun GlobalChatScreen(
    onOpenGlobal: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSamplePrivateChat: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContentHost(
                currentRoute = Routes.GLOBAL,
                onOpenGlobal = {
                    scope.launch {
                        drawerState.close()
                        onOpenGlobal()
                    }
                },
                onOpenContacts = {
                    scope.launch {
                        drawerState.close()
                        onOpenContacts()
                    }
                },
                onOpenNearby = {
                    scope.launch {
                        drawerState.close()
                        onOpenNearby()
                    }
                },
                onOpenSettings = {
                    scope.launch {
                        drawerState.close()
                        onOpenSettings()
                    }
                }
            )
        }
    ) {
        ChatScaffold(
            title = "Global Chat",
            subtitle = "UI components preview",
            messages = globalPreviewMessages,
            localUsername = "You",
            topBarUsername = "You",
            topBarRightAction = AuroraTopBarAction.MENU,
            onTopBarRightAction = {
                scope.launch { drawerState.open() }
            },
            composerHint = "Write a preview message",
            bodyTop = {
                TransportStatusCard(
                    summary = "Visual-only status",
                    detail = "This card is a UI placeholder and is not connected to real transport state.",
                    tone = TransportStatusTone.NEUTRAL,
                    note = "Nearby communication will be wired in later stages."
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Navigation shortcuts")
                    Button(
                        onClick = onOpenSamplePrivateChat,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Sample Private Chat")
                    }
                    OutlinedButton(
                        onClick = onOpenContacts,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Contacts")
                    }
                    OutlinedButton(
                        onClick = onOpenNearby,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Nearby Devices")
                    }
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Settings")
                    }
                }
            },
            onSend = {
                // Το send μένει σκόπιμα no-op μέχρι να προστεθεί πραγματική λογική συνομιλίας.
            }
        )
    }
}
