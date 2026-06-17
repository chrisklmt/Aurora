package gr.hua.aurora.state

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.ContactPreview
import gr.hua.aurora.model.NearbyDevicePreview

// Το AuroraUiState κρατά μόνο app/UI-level μνήμη για τις οθόνες και όχι πραγματική ροή δεδομένων.
data class AuroraUiState(
    val contacts: List<ContactPreview>,
    val nearbyDevices: List<NearbyDevicePreview>,
    val globalMessages: List<ChatMessage>,
    val privateMessagesByPeerId: Map<String, List<ChatMessage>>,
    val generatedUsername: String,
    val customUsername: String?,
    val useCustomUsernameInGlobalChat: Boolean,
    val desiredAvailability: AuroraAvailabilityPreference
) {
    val privateProfileUsername: String
        get() = customUsername?.takeIf { it.isNotBlank() } ?: generatedUsername

    val globalChatUsername: String
        get() = if (useCustomUsernameInGlobalChat) {
            privateProfileUsername
        } else {
            generatedUsername
        }
}
