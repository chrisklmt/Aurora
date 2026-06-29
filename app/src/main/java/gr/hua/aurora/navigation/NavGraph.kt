package gr.hua.aurora.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.hasSessionForPeer
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.state.AuroraBleRuntimeState
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.ui.screens.nearbyContactPeerId
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
    val nearbyVisiblePeerIds = bleRuntimeState.discoveredAuroraPeers
        .mapNotNull(::nearbyContactPeerId)
        .toSet()
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
                            val transportResult = submitGlobalQueuedMessage(
                                queuedMessage = queuedMessage,
                                currentUsername = { stateHolder.uiState.globalChatUsername },
                                submitTransport = bleRuntimeState.submitGlobalMeshMessage
                            )
                            stateHolder.handleGlobalMeshDeliveryResult(
                                messageId = queuedMessage.messageId,
                                result = transportResult
                            )
                        }
                    }
                },
                onRetryMessage = { messageId ->
                    val queuedMessage = stateHolder.retryGlobalOutgoingMessage(messageId)
                    if (queuedMessage != null) {
                        sendScope.launch {
                            val transportResult = submitGlobalQueuedMessage(
                                queuedMessage = queuedMessage,
                                currentUsername = { stateHolder.uiState.globalChatUsername },
                                submitTransport = bleRuntimeState.submitGlobalMeshMessage
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
                nearbyVisiblePeerIds = nearbyVisiblePeerIds,
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
            val hasRuntimeSession = bleRuntimeState.peerSessionDiagnostics.hasSessionForPeer(peerId)
            PrivateChatScreen(
                requestedPeerId = peerId,
                contact = selectedContact,
                privateChatIdentity = privateChatIdentity,
                hasRuntimeSession = hasRuntimeSession,
                isNearbyVisible = nearbyVisiblePeerIds.contains(peerId),
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
                        sendScope.launch {
                            val transportResult = submitPrivateQueuedMessage(
                                queuedMessage = queuedMessage,
                                peerId = peerId,
                                currentUsername = { stateHolder.uiState.privateProfileUsername },
                                resolvePrivateChatId = { targetPeerId ->
                                    stateHolder.privateChatIdentityForPeerId(targetPeerId)?.privateChatId
                                },
                                submitTransport = bleRuntimeState.submitPrivateChatMessage
                            )
                            stateHolder.handlePrivateChatDeliveryResult(
                                peerId = peerId,
                                messageId = queuedMessage.messageId,
                                result = transportResult
                            )
                        }
                    }
                },
                onRetryMessage = { messageId ->
                    val queuedMessage = stateHolder.retryPrivateChatOutgoingMessage(peerId, messageId)
                    if (queuedMessage != null) {
                        sendScope.launch {
                            val transportResult = submitPrivateQueuedMessage(
                                queuedMessage = queuedMessage,
                                peerId = peerId,
                                currentUsername = { stateHolder.uiState.privateProfileUsername },
                                resolvePrivateChatId = { targetPeerId ->
                                    stateHolder.privateChatIdentityForPeerId(targetPeerId)?.privateChatId
                                },
                                submitTransport = bleRuntimeState.submitPrivateChatMessage
                            )
                            stateHolder.handlePrivateChatDeliveryResult(
                                peerId = peerId,
                                messageId = queuedMessage.messageId,
                                result = transportResult
                            )
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
                wifiDirectRuntimeStatus = bleRuntimeState.wifiDirectRuntimeStatus,
                onStartWifiDirectDiscovery = bleRuntimeState.startWifiDirectDiscovery,
                onStopWifiDirectDiscovery = bleRuntimeState.stopWifiDirectDiscovery,
                onConnectWifiDirectPeer = bleRuntimeState.connectToWifiDirectPeer,
                onDisconnectWifiDirectPeer = bleRuntimeState.disconnectWifiDirectPeer,
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
                onRefreshContactLastSeen = { peerId, lastSeenMillis ->
                    stateHolder.refreshContactLastSeen(
                        canonicalPeerId = peerId,
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

internal suspend fun submitGlobalQueuedMessage(
    queuedMessage: OutgoingChatMessage,
    currentUsername: () -> String,
    submitTransport: suspend (OutgoingChatMessage, String) -> GlobalMeshDeliveryResult
): GlobalMeshDeliveryResult {
    return runCatching {
        submitTransport(
            queuedMessage,
            currentUsername().trim()
        )
    }.getOrElse { error ->
        GlobalMeshDeliveryResult.Failed(
            reason = error.message ?: "Public mesh transport submission failed."
        )
    }
}

internal suspend fun submitPrivateQueuedMessage(
    queuedMessage: OutgoingChatMessage,
    peerId: String,
    currentUsername: () -> String,
    resolvePrivateChatId: (String) -> String?,
    submitTransport: suspend (OutgoingChatMessage, String, String) -> PrivateChatMessageSendResult
): PrivateChatMessageSendResult {
    val privateChatId = resolvePrivateChatId(peerId)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return PrivateChatMessageSendResult.KeysUnavailable

    return runCatching {
        submitTransport(
            queuedMessage,
            currentUsername().trim(),
            privateChatId
        )
    }.getOrElse { error ->
        PrivateChatMessageSendResult.Failed(
            reason = error.message ?: "Private chat transport submission failed."
        )
    }
}
