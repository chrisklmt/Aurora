package gr.hua.aurora.data.persistence

import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.PrivateChatIdentity

data class PersistedAuroraState(
    val contacts: List<PersistedContact> = emptyList(),
    val messages: List<PersistedChatMessage> = emptyList(),
    val privateChats: List<PersistedPrivateChat> = emptyList()
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

data class PersistedPrivateChat(
    val canonicalPeerId: String,
    val privateChatId: String? = null,
    val localProposalId: String? = null,
    val remoteProposalId: String? = null,
    val customChatName: String? = null,
    val lastKnownRemoteUsername: String? = null,
    val createdAtMillis: Long,
    val lastUpdatedMillis: Long
) {
    init {
        require(canonicalPeerId.isNotBlank()) {
            "PersistedPrivateChat canonicalPeerId must not be blank."
        }
        require(privateChatId?.isNotBlank() != false) {
            "PersistedPrivateChat privateChatId must not be blank when present."
        }
        require(localProposalId?.isNotBlank() != false) {
            "PersistedPrivateChat localProposalId must not be blank when present."
        }
        require(remoteProposalId?.isNotBlank() != false) {
            "PersistedPrivateChat remoteProposalId must not be blank when present."
        }
        require(customChatName?.isNotBlank() != false) {
            "PersistedPrivateChat customChatName must not be blank when present."
        }
        require(lastKnownRemoteUsername?.isNotBlank() != false) {
            "PersistedPrivateChat lastKnownRemoteUsername must not be blank when present."
        }
        require(createdAtMillis >= 0L) {
            "PersistedPrivateChat createdAtMillis must be non-negative."
        }
        require(lastUpdatedMillis >= 0L) {
            "PersistedPrivateChat lastUpdatedMillis must be non-negative."
        }
    }

    fun toRestoredPrivateChatIdentity(): PrivateChatIdentity {
        return PrivateChatIdentity(
            canonicalPeerId = canonicalPeerId,
            privateChatId = privateChatId,
            localProposalId = localProposalId,
            remoteProposalId = remoteProposalId,
            customChatName = customChatName,
            lastKnownRemoteUsername = lastKnownRemoteUsername,
            createdAtMillis = createdAtMillis,
            lastUpdatedMillis = lastUpdatedMillis
        )
    }
}

interface AuroraPersistenceStore {
    fun load(): PersistedAuroraState

    fun saveContact(contact: PersistedContact)

    fun saveMessage(message: PersistedChatMessage)

    fun savePrivateChat(privateChat: PersistedPrivateChat)

    fun clear()

    fun replaceAll(state: PersistedAuroraState) {
        clear()
        state.contacts.forEach(::saveContact)
        state.messages.forEach(::saveMessage)
        state.privateChats.forEach(::savePrivateChat)
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

fun PrivateChatIdentity.toPersistedPrivateChat(): PersistedPrivateChat {
    return PersistedPrivateChat(
        canonicalPeerId = canonicalPeerId,
        privateChatId = privateChatId,
        localProposalId = localProposalId,
        remoteProposalId = remoteProposalId,
        customChatName = customChatName,
        lastKnownRemoteUsername = lastKnownRemoteUsername,
        createdAtMillis = createdAtMillis,
        lastUpdatedMillis = lastUpdatedMillis
    )
}
