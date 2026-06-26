package gr.hua.aurora.data.persistence

class InMemoryAuroraPersistenceStore(
    initialState: PersistedAuroraState = PersistedAuroraState()
) : AuroraPersistenceStore {
    private val contactsByPeerId = LinkedHashMap<String, PersistedContact>()
    private val messagesById = LinkedHashMap<String, PersistedChatMessage>()

    init {
        initialState.contacts.forEach(::saveContact)
        initialState.messages.forEach(::saveMessage)
    }

    override fun load(): PersistedAuroraState {
        return PersistedAuroraState(
            contacts = contactsByPeerId.values
                .map { it.copy() }
                .sortedWith(
                    compareBy<PersistedContact>({ it.displayName.lowercase() }, { it.canonicalPeerId })
                ),
            messages = messagesById.values
                .map { it.copy() }
                .sortedWith(
                    compareBy<PersistedChatMessage>({ it.createdAtMillis }, { it.messageId })
                )
        )
    }

    override fun saveContact(contact: PersistedContact) {
        contactsByPeerId[contact.canonicalPeerId] = contact.copy()
    }

    override fun saveMessage(message: PersistedChatMessage) {
        messagesById[message.messageId] = message.copy()
    }

    override fun clear() {
        contactsByPeerId.clear()
        messagesById.clear()
    }
}
