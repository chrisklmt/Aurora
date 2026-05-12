package gr.hua.aurora.state

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.ContactPreview
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.TransportType

object SampleAuroraState {
    // Τα seed δεδομένα υπάρχουν μόνο για να μείνουν διαδραστικές οι οθόνες πριν προστεθεί πραγματικό data flow.
    fun create(): AuroraUiState {
        val now = System.currentTimeMillis()

        return AuroraUiState(
            contacts = listOf(
                ContactPreview(
                    id = "alex",
                    displayName = "Alex",
                    detail = "Available for preview chat.",
                    lastSeenAtMillis = now - 4 * 60_000L,
                    preferredTransport = TransportType.BLE,
                    isTrusted = true
                ),
                ContactPreview(
                    id = "maria",
                    displayName = "Maria",
                    detail = "Visual-only contact entry.",
                    lastSeenAtMillis = now - 11 * 60_000L,
                    preferredTransport = TransportType.WIFI_DIRECT
                ),
                ContactPreview(
                    id = "nikos",
                    displayName = "Nikos",
                    detail = "Placeholder contact for the list layout."
                )
            ),
            globalMessages = listOf(
                ChatMessage(
                    id = "global-1",
                    threadId = "global",
                    senderId = "system",
                    senderName = "Aurora",
                    text = "The global chat layout is now rendered with reusable Compose components.",
                    createdAtMillis = now - 8 * 60_000L,
                    status = MessageStatus.DELIVERED,
                    isOutgoing = false
                ),
                ChatMessage(
                    id = "global-2",
                    threadId = "global",
                    senderId = "self",
                    senderName = "You",
                    text = "This screen now uses local in-memory state without real transport logic.",
                    createdAtMillis = now - 6 * 60_000L,
                    status = MessageStatus.SENT,
                    isOutgoing = true
                ),
                ChatMessage(
                    id = "global-3",
                    threadId = "global",
                    senderId = "system",
                    senderName = "Aurora",
                    text = "Nearby, settings, and contacts remain UI-only in this stage.",
                    createdAtMillis = now - 4 * 60_000L,
                    status = MessageStatus.DELIVERED,
                    isOutgoing = false
                )
            ),
            privateMessagesByPeerId = mapOf(
                "alex" to listOf(
                    ChatMessage(
                        id = "alex-1",
                        threadId = "private:alex",
                        senderId = "alex",
                        senderName = "Alex",
                        text = "This private conversation is backed by local preview state.",
                        createdAtMillis = now - 9 * 60_000L,
                        status = MessageStatus.DELIVERED,
                        isOutgoing = false
                    ),
                    ChatMessage(
                        id = "alex-2",
                        threadId = "private:alex",
                        senderId = "self",
                        senderName = "You",
                        text = "The screen layout is ready before real peer communication is added.",
                        createdAtMillis = now - 7 * 60_000L,
                        status = MessageStatus.SENT,
                        isOutgoing = true
                    )
                ),
                "maria" to listOf(
                    ChatMessage(
                        id = "maria-1",
                        threadId = "private:maria",
                        senderId = "maria",
                        senderName = "Maria",
                        text = "This thread exists only in local memory for UI wiring.",
                        createdAtMillis = now - 5 * 60_000L,
                        status = MessageStatus.QUEUED,
                        isOutgoing = false
                    )
                )
            ),
            currentUsername = "You"
        )
    }
}
