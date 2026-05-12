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

    NavHost(
        navController = navController,
        startDestination = Routes.GLOBAL,
        modifier = modifier
    ) {
        composable(Routes.GLOBAL) {
            GlobalChatScreen(
                currentUsername = uiState.currentUsername,
                messages = uiState.globalMessages,
                onOpenGlobal = {
                    navController.navigate(Routes.GLOBAL) {
                        launchSingleTop = true
                    }
                },
                onOpenContacts = {
                    navController.navigate(Routes.CONTACTS) {
                        launchSingleTop = true
                    }
                },
                onOpenNearby = { navController.navigate(Routes.NEARBY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSendMessage = stateHolder::sendGlobalPreviewMessage
            )
        }

        composable(Routes.CONTACTS) {
            ContactsScreen(
                contacts = uiState.contacts,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PRIVATE_ROUTE) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString(Routes.PRIVATE_ARG) ?: "unknown-peer"
            PrivateChatScreen(
                peerId = peerId,
                peerDisplayName = stateHolder.displayNameForPeerId(peerId),
                currentUsername = uiState.currentUsername,
                messages = stateHolder.privateMessagesForPeerId(peerId),
                onBack = { navController.popBackStack() },
                onSendMessage = { text ->
                    stateHolder.sendPrivatePreviewMessage(peerId, text)
                }
            )
        }

        composable(Routes.NEARBY) {
            NearbyDevicesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                currentUsername = uiState.currentUsername,
                onUsernameChange = stateHolder::updateUsername,
                onClearLocalData = stateHolder::resetLocalData,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
