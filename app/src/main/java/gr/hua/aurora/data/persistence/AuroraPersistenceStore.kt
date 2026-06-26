package gr.hua.aurora.data.persistence

import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus

data class PersistedAuroraState(
    val contacts: List<PersistedContact> = emptyList(),
    val messages: List<PersistedChatMessage> = emptyList()
)

data class PersistedContact(
    val canonicalPeerId: String,
    val displayName: String,
    val createdAtMillis: Long,
    val lastSeenMillis: Long? = null
) {
    init {
        require(canonicalPeerId.isNotBlank()) {
            "PersistedContact canonicalPeerId must not be blank."
        }
        require(displayName.isNotBlank()) {
            "PersistedContact displayName must not be blank."
        }
        require(createdAtMillis >= 0L) {
            "PersistedContact createdAtMillis must be non-negative."
        }
    }

    fun toRestoredContact(): AuroraContact {
        return AuroraContact(
            canonicalPeerId = canonicalPeerId,
            displayName = displayName,
            createdAtMillis = createdAtMillis,
            lastSeenMillis = lastSeenMillis,
            hasSession = false
        )
    }
}

enum class PersistedChatThreadType {
    GLOBAL,
    PRIVATE
}

enum class PersistedMessageDirection {
    INCOMING,
    OUTGOING
}

data class PersistedChatMessage(
    val messageId: String,
    val threadType: PersistedChatThreadType,
    val peerId: String? = null,
    val text: String,
    val createdAtMillis: Long,
    val direction: PersistedMessageDirection,
    val status: MessageStatus,
    val senderId: String,
    val senderName: String
) {
    init {
        require(messageId.isNotBlank()) {
            "PersistedChatMessage messageId must not be blank."
        }
        require(text.isNotBlank()) {
            "PersistedChatMessage text must not be blank."
        }
        require(createdAtMillis >= 0L) {
            "PersistedChatMessage createdAtMillis must be non-negative."
        }
        require(senderId.isNotBlank()) {
            "PersistedChatMessage senderId must not be blank."
        }
        require(senderName.isNotBlank()) {
            "PersistedChatMessage senderName must not be blank."
        }
        when (threadType) {
            PersistedChatThreadType.GLOBAL -> require(peerId == null) {
                "Persisted global chat messages must not carry a peerId."
            }
            PersistedChatThreadType.PRIVATE -> require(!peerId.isNullOrBlank()) {
                "Persisted private chat messages must carry a peerId."
            }
        }
    }

    val threadId: String
        get() = when (threadType) {
            PersistedChatThreadType.GLOBAL -> "global"
            PersistedChatThreadType.PRIVATE -> "private:${requireNotNull(peerId)}"
        }

    fun toRestoredChatMessage(): ChatMessage {
        return ChatMessage(
            id = messageId,
            threadId = threadId,
            senderId = senderId,
            senderName = senderName,
            text = text,
            createdAtMillis = createdAtMillis,
            status = restoredStatus(),
            isOutgoing = direction == PersistedMessageDirection.OUTGOING
        )
    }

    private fun restoredStatus(): MessageStatus {
        if (direction == PersistedMessageDirection.INCOMING) {
            return MessageStatus.RECEIVED
        }

        return when (status) {
            MessageStatus.DRAFT,
            MessageStatus.LOCAL_ONLY,
            MessageStatus.QUEUED,
            MessageStatus.RECEIVED -> MessageStatus.FAILED

            MessageStatus.SENT -> MessageStatus.SENT
            MessageStatus.DELIVERED -> MessageStatus.SENT
            MessageStatus.FAILED -> MessageStatus.FAILED
        }
    }
}

interface AuroraPersistenceStore {
    fun load(): PersistedAuroraState

    fun saveContact(contact: PersistedContact)

    fun saveMessage(message: PersistedChatMessage)

    fun clear()

    fun replaceAll(state: PersistedAuroraState) {
        clear()
        state.contacts.forEach(::saveContact)
        state.messages.forEach(::saveMessage)
    }
}

fun AuroraContact.toPersistedContact(): PersistedContact {
    return PersistedContact(
        canonicalPeerId = canonicalPeerId,
        displayName = displayName,
        createdAtMillis = createdAtMillis,
        lastSeenMillis = lastSeenMillis
    )
}

fun ChatMessage.toPersistedChatMessage(): PersistedChatMessage {
    val resolvedThreadType = when {
        threadId == "global" -> PersistedChatThreadType.GLOBAL
        threadId.startsWith("private:") -> PersistedChatThreadType.PRIVATE
        else -> throw IllegalArgumentException(
            "Unsupported chat threadId for persistence: $threadId"
        )
    }

    return PersistedChatMessage(
        messageId = id,
        threadType = resolvedThreadType,
        peerId = when (resolvedThreadType) {
            PersistedChatThreadType.GLOBAL -> null
            PersistedChatThreadType.PRIVATE -> threadId.removePrefix("private:")
        },
        text = text,
        createdAtMillis = createdAtMillis,
        direction = if (isOutgoing) {
            PersistedMessageDirection.OUTGOING
        } else {
            PersistedMessageDirection.INCOMING
        },
        status = status,
        senderId = senderId,
        senderName = senderName
    )
}
