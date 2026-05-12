package gr.hua.aurora.ui.screens

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.navigation.Routes
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatScaffold
import gr.hua.aurora.ui.components.TransportStatusCard
import gr.hua.aurora.ui.components.TransportStatusTone
import gr.hua.aurora.ui.components.toMessageListItem
import kotlinx.coroutines.launch

@Composable
fun GlobalChatScreen(
    currentUsername: String,
    messages: List<ChatMessage>,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    onSendMessage: (String) -> Unit,
    onResetLocalData: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val mappedMessages = messages.map { it.toMessageListItem() }

    // Χρησιμοποιούμε τοπικά RTL μόνο για το drawer container ώστε το panel να ανοίγει από δεξιά
    // χωρίς να αλλάζει η κατεύθυνση στο υπόλοιπο chat UI.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DrawerContentHost(
                        currentRoute = Routes.GLOBAL,
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
                    messages = mappedMessages,
                    localUsername = currentUsername,
                    topBarUsername = currentUsername,
                    onTopBarUsernameTripleTap = onResetLocalData,
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
                    onSend = onSendMessage
                )
            }
        }
    }
}
