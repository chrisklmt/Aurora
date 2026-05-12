package gr.hua.aurora.ui.screens

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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

    // Χρησιμοποιούμε τοπικά RTL μόνο για το drawer container ώστε το panel να ανοίγει από δεξιά
    // χωρίς να αλλάζει η κατεύθυνση στο υπόλοιπο chat UI.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
                    },
                    onSend = {
                        // Το send μένει σκόπιμα no-op μέχρι να προστεθεί πραγματική λογική συνομιλίας.
                    }
                )
            }
        }
    }
}
