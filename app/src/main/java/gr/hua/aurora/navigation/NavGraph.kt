package gr.hua.aurora.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.ui.screens.ContactsScreen
import gr.hua.aurora.ui.screens.GlobalChatScreen
import gr.hua.aurora.ui.screens.NearbyDevicesScreen
import gr.hua.aurora.ui.screens.PrivateChatScreen
import gr.hua.aurora.ui.screens.SettingsScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    stateHolder: AuroraStateHolder,
    modifier: Modifier = Modifier
) {
    val uiState = stateHolder.uiState
    val onNavigateBackOrGlobal: () -> Unit = {
        if (!navController.popBackStack()) {
            navController.navigate(Routes.GLOBAL) {
                launchSingleTop = true
                popUpTo(Routes.GLOBAL) {
                    inclusive = false
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.GLOBAL,
        modifier = modifier
    ) {
        composable(Routes.GLOBAL) {
            GlobalChatScreen(
                currentUsername = uiState.globalChatUsername,
                messages = uiState.globalMessages,
                onOpenContacts = {
                    navController.navigate(Routes.CONTACTS) {
                        launchSingleTop = true
                    }
                },
                onOpenNearby = { navController.navigate(Routes.NEARBY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSendMessage = stateHolder::sendGlobalPreviewMessage,
                onResetLocalData = stateHolder::resetLocalData
            )
        }

        composable(Routes.CONTACTS) {
            ContactsScreen(
                contacts = uiState.contacts,
                currentUsername = uiState.privateProfileUsername,
                onResetLocalData = stateHolder::resetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }

        composable(Routes.PRIVATE_ROUTE) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString(Routes.PRIVATE_ARG) ?: "unknown-peer"
            PrivateChatScreen(
                peerId = peerId,
                peerDisplayName = stateHolder.displayNameForPeerId(peerId),
                currentUsername = uiState.privateProfileUsername,
                messages = stateHolder.privateMessagesForPeerId(peerId),
                onBack = onNavigateBackOrGlobal,
                onSendMessage = { text ->
                    stateHolder.sendPrivatePreviewMessage(peerId, text)
                },
                onResetLocalData = stateHolder::resetLocalData
            )
        }

        composable(Routes.NEARBY) {
            NearbyDevicesScreen(
                nearbyDevices = uiState.nearbyDevices,
                currentUsername = uiState.privateProfileUsername,
                onResetLocalData = stateHolder::resetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                currentUsername = uiState.privateProfileUsername,
                generatedUsername = uiState.generatedUsername,
                useCustomUsernameInGlobalChat = uiState.useCustomUsernameInGlobalChat,
                onUsernameChange = stateHolder::updateUsername,
                onUseCustomUsernameInGlobalChatChange = stateHolder::updateUseCustomUsernameInGlobalChat,
                onClearLocalData = stateHolder::resetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }
    }
}
