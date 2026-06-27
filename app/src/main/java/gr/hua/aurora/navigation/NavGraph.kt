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
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
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
    val onResetLocalData: () -> Unit = {
        bleRuntimeState.resetLocalIdentityAndSessions()
        stateHolder.resetLocalData()
    }
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
                onResetLocalData = onResetLocalData
            )
        }

        composable(Routes.CONTACTS) {
            ContactsScreen(
                contacts = uiState.contacts,
                privateChatIdentitiesByPeerId = uiState.privateChatIdentitiesByPeerId,
                currentUsername = uiState.privateProfileUsername,
                desiredAvailability = uiState.desiredAvailability,
                showDebugDiagnostics = uiState.isDebugModeEnabled,
                peerSessionDiagnostics = bleRuntimeState.peerSessionDiagnostics,
                lastIdentityExchangeStatus = bleRuntimeState.lastIdentityExchangeStatus,
                onOpenChat = { peerId ->
                    navController.navigate(Routes.privateChat(peerId))
                },
                onRenameChat = { peerId, customName ->
                    stateHolder.renamePrivateChat(peerId, customName)
                },
                onDeleteChat = stateHolder::deletePrivateChat,
                onResetLocalData = onResetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }

        composable(Routes.PRIVATE_ROUTE) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString(Routes.PRIVATE_ARG) ?: "unknown-peer"
            val selectedContact = stateHolder.findContactByPeerId(peerId)
            val privateChatIdentity = stateHolder.privateChatIdentityForPeerId(peerId)
            PrivateChatScreen(
                requestedPeerId = peerId,
                contact = selectedContact,
                privateChatIdentity = privateChatIdentity,
                currentUsername = uiState.privateProfileUsername,
                messages = stateHolder.privateMessagesForPeerId(peerId),
                lastDeliveryResult = stateHolder.latestPrivateChatDeliveryResultForPeerId(peerId),
                showDebugDiagnostics = uiState.isDebugModeEnabled,
                peerSessionDiagnostics = bleRuntimeState.peerSessionDiagnostics,
                activeTransportPeerId = bleRuntimeState.activeTransportPeerId,
                lastIdentityExchangeStatus = bleRuntimeState.lastIdentityExchangeStatus,
                onBack = onNavigateBackOrGlobal,
                onSendMessage = { text ->
                    val queuedMessage = stateHolder.sendPrivateChatMessage(peerId, text)
                    if (queuedMessage != null) {
                        val privateChatId = stateHolder.privateChatIdentityForPeerId(peerId)?.privateChatId
                        if (privateChatId == null) {
                            stateHolder.handlePrivateChatDeliveryResult(
                                peerId = peerId,
                                messageId = queuedMessage.messageId,
                                result = PrivateChatMessageSendResult.KeysUnavailable
                            )
                        } else {
                            sendScope.launch {
                                val transportResult = runCatching {
                                    bleRuntimeState.submitPrivateChatMessage(
                                        queuedMessage,
                                        uiState.privateProfileUsername,
                                        privateChatId
                                    )
                                }.getOrElse { error ->
                                    PrivateChatMessageSendResult.Failed(
                                        reason = error.message ?: "Private chat transport submission failed."
                                    )
                                }
                                stateHolder.handlePrivateChatDeliveryResult(
                                    peerId = peerId,
                                    messageId = queuedMessage.messageId,
                                    result = transportResult
                                )
                            }
                        }
                    }
                },
                onResetLocalData = onResetLocalData
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
                privateChatIdentitiesByPeerId = uiState.privateChatIdentitiesByPeerId,
                transportSenderSourceLabel = bleRuntimeState.transportSenderSourceLabel,
                identityHandlerStatus = bleRuntimeState.identityHandlerStatus,
                peerSessionDiagnostics = bleRuntimeState.peerSessionDiagnostics,
                activeTransportPeerId = bleRuntimeState.activeTransportPeerId,
                activeTransportDeviceAddress = bleRuntimeState.activeTransportDeviceAddress,
                selectedSecurePeerId = uiState.selectedSecurePeerId,
                lastIdentityExchangeStatus = bleRuntimeState.lastIdentityExchangeStatus,
                onExchangeIdentityWithPeer = bleRuntimeState.exchangeIdentityWithPeer,
                onConnectTransportPeer = bleRuntimeState.connectToTransportPeer,
                onDisconnectTransportPeer = bleRuntimeState.disconnectTransportPeer,
                onAddOrUpdateContact = { peerId, displayName, lastSeenMillis, hasSession ->
                    stateHolder.addOrUpdateContact(
                        canonicalPeerId = peerId,
                        displayName = displayName,
                        lastSeenMillis = lastSeenMillis,
                        hasSession = hasSession
                    )
                    stateHolder.privateChatIdentityForPeerId(peerId)?.localProposalId
                },
                onPromoteContactSession = { peerId, displayName, lastSeenMillis ->
                    stateHolder.promoteContactSession(
                        canonicalPeerId = peerId,
                        displayName = displayName,
                        lastSeenMillis = lastSeenMillis
                    )
                },
                onSelectSecurePeer = stateHolder::selectSecurePeer,
                onClearSelectedSecurePeer = stateHolder::clearSelectedSecurePeer,
                onOpenPrivateChat = { peerId ->
                    navController.navigate(Routes.privateChat(peerId))
                },
                onResetLocalData = onResetLocalData,
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
                onClearLocalData = onResetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }
    }
}
