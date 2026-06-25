package gr.hua.aurora.state

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.NearbyDevicePreview
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult

// Το AuroraUiState κρατά μόνο app/UI-level μνήμη για τις οθόνες και όχι πραγματική ροή δεδομένων.
data class AuroraUiState(
    val contacts: List<AuroraContact>,
    val nearbyDevices: List<NearbyDevicePreview>,
    val globalMessages: List<ChatMessage>,
    val pendingOutgoingMessages: List<OutgoingChatMessage>,
    val privateMessagesByPeerId: Map<String, List<ChatMessage>>,
    val generatedUsername: String,
    val customUsername: String?,
    val useCustomUsernameInGlobalChat: Boolean,
    val isDebugModeEnabled: Boolean,
    val desiredAvailability: AuroraAvailabilityPreference,
    val selectedSecurePeerId: String? = null,
    val globalMeshDeliveryResult: GlobalMeshDeliveryResult? = null
) {
    val privateProfileUsername: String
        get() = customUsername?.takeIf { it.isNotBlank() } ?: generatedUsername

    val globalChatUsername: String
        get() = if (useCustomUsernameInGlobalChat) {
            privateProfileUsername
        } else {
            generatedUsername
        }

    val globalPendingOutgoingCount: Int
        get() = pendingOutgoingMessages.count { it.threadId == "global" }
}
