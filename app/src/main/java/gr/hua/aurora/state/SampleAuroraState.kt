package gr.hua.aurora.state

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.NearbyDevicePreview
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.model.TransportType

object SampleAuroraState {
    // Τα seed δεδομένα υπάρχουν μόνο για να μείνουν διαδραστικές οι οθόνες πριν προστεθεί πραγματικό data flow.
    fun create(
        generatedUsername: String,
        customUsername: String? = null,
        useCustomUsernameInGlobalChat: Boolean = true,
        isDebugModeEnabled: Boolean = false,
        desiredAvailability: AuroraAvailabilityPreference = AuroraAvailabilityPreference.ONLINE
    ): AuroraUiState {
        val now = System.currentTimeMillis()
        val resolvedGeneratedUsername = generatedUsername.trim()
        val resolvedCustomUsername = customUsername?.trim()?.takeIf { it.isNotEmpty() }
        val privateProfileUsername = resolvedCustomUsername ?: resolvedGeneratedUsername

        require(resolvedGeneratedUsername.isNotEmpty()) { "generatedUsername must not be blank." }

        return AuroraUiState(
            contacts = emptyList(),
            nearbyDevices = listOf(
                NearbyDevicePreview(
                    id = "peer-alex",
                    displayName = "Alex's Phone",
                    detail = "Preview item for nearby list layout.",
                    transportType = TransportType.BLE,
                    signalLabel = "-58 dBm",
                    isConnectable = true
                ),
                NearbyDevicePreview(
                    id = "peer-maria",
                    displayName = "Maria Tablet",
                    detail = "Visual-only nearby entry without active discovery.",
                    transportType = TransportType.WIFI_DIRECT,
                    signalLabel = "Strong",
                    isConnectable = true
                ),
                NearbyDevicePreview(
                    id = "peer-kiosk",
                    displayName = "Campus Kiosk",
                    detail = "Sample device card for unavailable connection state.",
                    transportType = TransportType.UNKNOWN,
                    signalLabel = "Unknown",
                    isConnectable = false
                )
            ),
            globalMessages = emptyList(),
            pendingOutgoingMessages = emptyList<OutgoingChatMessage>(),
            privateMessagesByPeerId = emptyMap<String, List<ChatMessage>>(),
            generatedUsername = resolvedGeneratedUsername,
            customUsername = resolvedCustomUsername,
            useCustomUsernameInGlobalChat = useCustomUsernameInGlobalChat,
            isDebugModeEnabled = isDebugModeEnabled,
            desiredAvailability = desiredAvailability,
            selectedSecurePeerId = null,
            globalMeshDeliveryResult = null
        )
    }
}
