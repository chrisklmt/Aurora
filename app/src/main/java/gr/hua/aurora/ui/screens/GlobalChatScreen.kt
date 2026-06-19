package gr.hua.aurora.ui.screens

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.ui.components.AuroraAvailabilityIndicator
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatScaffold
import gr.hua.aurora.ui.components.rememberAuroraAvailabilityUiState
import gr.hua.aurora.ui.components.toMessageListItem
import kotlinx.coroutines.launch

@Composable
fun GlobalChatScreen(
    currentUsername: String,
    messages: List<ChatMessage>,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    desiredAvailability: AuroraAvailabilityPreference,
    onDesiredAvailabilityChange: (AuroraAvailabilityPreference) -> Unit,
    onSendMessage: (String) -> Unit,
    onResetLocalData: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val mappedMessages = messages.map { it.toMessageListItem() }
    val availabilityState = rememberAuroraAvailabilityUiState(desiredAvailability)
    val localMessagingNote = if (desiredAvailability == AuroraAvailabilityPreference.OFFLINE) {
        "Offline: messages stay on this device."
    } else {
        "Messages are saved locally until mesh delivery is connected."
    }

    // Χρησιμοποιούμε τοπικά RTL μόνο για το drawer container ώστε το panel να ανοίγει από δεξιά
    // χωρίς να αλλάζει η κατεύθυνση στο υπόλοιπο chat UI.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DrawerContentHost(
                        desiredAvailability = desiredAvailability,
                        onDesiredAvailabilityChange = onDesiredAvailabilityChange,
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
                    subtitle = null,
                    topBarSubtitleContent = {
                        AuroraAvailabilityIndicator(uiState = availabilityState.uiState)
                    },
                    messages = mappedMessages,
                    topBarUsername = currentUsername,
                    onTopBarUsernameTripleTap = onResetLocalData,
                    topBarRightAction = AuroraTopBarAction.MENU,
                    onTopBarRightAction = {
                        scope.launch { drawerState.open() }
                    },
                    composerHint = "Write a message",
                    bodyTop = {
                        Text(
                            text = localMessagingNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onSend = onSendMessage
                )
            }
        }
    }
}
