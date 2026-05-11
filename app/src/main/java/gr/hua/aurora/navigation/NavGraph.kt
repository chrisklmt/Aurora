package gr.hua.aurora.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import gr.hua.aurora.ui.screens.GlobalChatScreen
import gr.hua.aurora.ui.screens.NearbyDevicesScreen
import gr.hua.aurora.ui.screens.PrivateChatScreen
import gr.hua.aurora.ui.screens.SettingsScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.GLOBAL,
        modifier = modifier
    ) {
        composable(Routes.GLOBAL) {
            GlobalChatScreen(
                onOpenNearby = { navController.navigate(Routes.NEARBY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSamplePrivateChat = {
                    navController.navigate(Routes.privateChat("demo-peer"))
                }
            )
        }

        composable(Routes.PRIVATE_ROUTE) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString(Routes.PRIVATE_ARG) ?: "unknown-peer"
            PrivateChatScreen(
                peerId = peerId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.NEARBY) {
            NearbyDevicesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
