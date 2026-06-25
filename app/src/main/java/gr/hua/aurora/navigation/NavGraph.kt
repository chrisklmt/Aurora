package gr.hua.aurora.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.state.AuroraBleRuntimeState
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.ui.screens.ContactsScreen
import gr.hua.aurora.ui.screens.GlobalChatScreen
import gr.hua.aurora.ui.screens.NearbyDevicesScreen
import gr.hua.aurora.ui.screens.PrivateChatScreen
import gr.hua.aurora.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun NavGraph(
    navController: NavHostController,
    stateHolder: AuroraStateHolder,
    bleRuntimeState: AuroraBleRuntimeState,
    modifier: Modifier = Modifier
) {
    val uiState = stateHolder.uiState
    val sendScope = rememberCoroutineScope()
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
                queuedOutgoingCount = uiState.globalPendingOutgoingCount,
                transportSenderSourceLabel = bleRuntimeState.transportSenderSourceLabel,
                globalMeshDiagnostics = bleRuntimeState.globalMeshDiagnostics,
                lastIncomingMessageStatus = bleRuntimeState.lastIncomingMessageStatus,
                lastConnectOnSendStatus = bleRuntimeState.lastConnectOnSendStatus,
                lastGlobalMeshStatus = bleRuntimeState.lastGlobalMeshStatus,
                meshDeliveryResult = uiState.globalMeshDeliveryResult,
                showDebugDiagnostics = uiState.isDebugModeEnabled,
                onOpenContacts = {
                    navController.navigate(Routes.CONTACTS) {
                        launchSingleTop = true
                    }
                },
                onOpenNearby = { navController.navigate(Routes.NEARBY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                desiredAvailability = uiState.desiredAvailability,
                onDesiredAvailabilityChange = { preference: AuroraAvailabilityPreference ->
                    stateHolder.updateDesiredAvailability(preference)
                },
                onSendMessage = { text ->
                    val queuedMessage = stateHolder.sendGlobalPreviewMessage(text)
                    if (queuedMessage != null) {
                        sendScope.launch {
                            val transportResult = bleRuntimeState.submitGlobalMeshMessage(
                                queuedMessage,
                                uiState.globalChatUsername
                            )
                            stateHolder.handleGlobalMeshDeliveryResult(
                                messageId = queuedMessage.messageId,
                                result = transportResult
                            )
                        }
                    }
                },
                onResetLocalData = stateHolder::resetLocalData
            )
        }

        composable(Routes.CONTACTS) {
            ContactsScreen(
                contacts = uiState.contacts,
                currentUsername = uiState.privateProfileUsername,
                desiredAvailability = uiState.desiredAvailability,
                onOpenChat = { peerId ->
                    navController.navigate(Routes.privateChat(peerId))
                },
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
                contacts = uiState.contacts,
                currentUsername = uiState.privateProfileUsername,
                desiredAvailability = uiState.desiredAvailability,
                bleAdvertiseStatus = bleRuntimeState.bleAdvertiseStatus,
                bleGattServerStatus = bleRuntimeState.bleGattServerStatus,
                bleScanStatus = bleRuntimeState.bleScanStatus,
                bleScanDiagnostics = bleRuntimeState.bleScanDiagnostics,
                discoveredBleDevices = bleRuntimeState.discoveredAuroraPeers,
                showDebugDiagnostics = uiState.isDebugModeEnabled,
                bleConnectionStatus = bleRuntimeState.bleConnectionStatus,
                bleConnector = bleRuntimeState.bleConnector,
                bleTransportSender = bleRuntimeState.bleTransportSender,
                transportSenderSourceLabel = bleRuntimeState.transportSenderSourceLabel,
                identityHandlerStatus = bleRuntimeState.identityHandlerStatus,
                peerSessionDiagnostics = bleRuntimeState.peerSessionDiagnostics,
                activeTransportPeerId = bleRuntimeState.activeTransportPeerId,
                activeTransportDeviceAddress = bleRuntimeState.activeTransportDeviceAddress,
                selectedSecurePeerId = uiState.selectedSecurePeerId,
                lastIdentityExchangeStatus = bleRuntimeState.lastIdentityExchangeStatus,
                onConnectTransportPeer = bleRuntimeState.connectToTransportPeer,
                onDisconnectTransportPeer = bleRuntimeState.disconnectTransportPeer,
                onAddOrUpdateContact = { peerId, displayName, lastSeenMillis, hasSession ->
                    stateHolder.addOrUpdateContact(
                        canonicalPeerId = peerId,
                        displayName = displayName,
                        lastSeenMillis = lastSeenMillis,
                        hasSession = hasSession
                    )
                },
                onSelectSecurePeer = stateHolder::selectSecurePeer,
                onClearSelectedSecurePeer = stateHolder::clearSelectedSecurePeer,
                onOpenPrivateChat = { peerId ->
                    navController.navigate(Routes.privateChat(peerId))
                },
                onResetLocalData = stateHolder::resetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                currentUsername = uiState.privateProfileUsername,
                generatedUsername = uiState.generatedUsername,
                useCustomUsernameInGlobalChat = uiState.useCustomUsernameInGlobalChat,
                isDebugModeEnabled = uiState.isDebugModeEnabled,
                onUsernameChange = stateHolder::updateUsername,
                onUseCustomUsernameInGlobalChatChange = stateHolder::updateUseCustomUsernameInGlobalChat,
                onDebugModeChange = stateHolder::updateDebugMode,
                onClearLocalData = stateHolder::resetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }
    }
}
