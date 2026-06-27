package gr.hua.aurora.data.persistence

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

class FileAuroraPersistenceStore(
    private val stateFile: File
) : AuroraPersistenceStore {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, stateFileName)
    )

    private val lock = Any()

    override fun load(): PersistedAuroraState {
        synchronized(lock) {
            return loadUnlocked()
        }
    }

    override fun saveContact(contact: PersistedContact) {
        synchronized(lock) {
            val currentState = loadUnlocked()
            val updatedContacts = currentState.contacts
                .filterNot { it.canonicalPeerId == contact.canonicalPeerId } + contact.copy()
            writeUnlocked(
                PersistedAuroraState(
                    contacts = updatedContacts,
                    messages = currentState.messages,
                    privateChats = currentState.privateChats
                )
            )
        }
    }

    override fun saveMessage(message: PersistedChatMessage) {
        synchronized(lock) {
            val currentState = loadUnlocked()
            val updatedMessages = currentState.messages
                .filterNot { it.messageId == message.messageId } + message.copy()
            writeUnlocked(
                PersistedAuroraState(
                    contacts = currentState.contacts,
                    messages = updatedMessages,
                    privateChats = currentState.privateChats
                )
            )
        }
    }

    override fun savePrivateChat(privateChat: PersistedPrivateChat) {
        synchronized(lock) {
            val currentState = loadUnlocked()
            val updatedPrivateChats = currentState.privateChats
                .filterNot { it.canonicalPeerId == privateChat.canonicalPeerId } + privateChat.copy()
            writeUnlocked(
                PersistedAuroraState(
                    contacts = currentState.contacts,
                    messages = currentState.messages,
                    privateChats = updatedPrivateChats
                )
            )
        }
    }

    override fun clear() {
        synchronized(lock) {
            if (stateFile.exists()) {
                stateFile.delete()
            }
        }
    }

    override fun replaceAll(state: PersistedAuroraState) {
        synchronized(lock) {
            writeUnlocked(state)
        }
    }

    private fun loadUnlocked(): PersistedAuroraState {
        if (!stateFile.exists()) {
            return PersistedAuroraState()
        }

        return runCatching {
            parseState(stateFile.readLines(UTF_8))
        }.getOrDefault(PersistedAuroraState())
    }

    private fun writeUnlocked(state: PersistedAuroraState) {
        val sortedState = PersistedAuroraState(
            contacts = state.contacts.sortedWith(
                compareBy<PersistedContact>({ it.displayName.lowercase() }, { it.canonicalPeerId })
            ),
            messages = state.messages.sortedWith(
                compareBy<PersistedChatMessage>({ it.createdAtMillis }, { it.messageId })
            ),
            privateChats = state.privateChats.sortedWith(
                compareBy<PersistedPrivateChat>({ it.lastUpdatedMillis }, { it.canonicalPeerId })
            )
        )
        val parentDirectory = stateFile.parentFile
        if (parentDirectory != null && !parentDirectory.exists()) {
            parentDirectory.mkdirs()
        }

        val tempFile = File(parentDirectory ?: stateFile.parentFile ?: File("."), "${stateFile.name}.tmp")
        tempFile.writeText(encodeState(sortedState), UTF_8)
        if (stateFile.exists()) {
            stateFile.delete()
        }
        if (!tempFile.renameTo(stateFile)) {
            tempFile.copyTo(stateFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun parseState(lines: List<String>): PersistedAuroraState {
        if (lines.isEmpty() || lines.first() != fileFormatHeader) {
            return PersistedAuroraState()
        }

        val contacts = mutableListOf<PersistedContact>()
        val messages = mutableListOf<PersistedChatMessage>()
        val privateChats = mutableListOf<PersistedPrivateChat>()
        lines.drop(1)
            .filter { it.isNotBlank() }
            .forEach { line ->
                when {
                    line.startsWith("$contactPrefix\t") -> {
                        decodeContact(line)?.let(contacts::add)
                    }
                    line.startsWith("$messagePrefix\t") -> {
                        decodeMessage(line)?.let(messages::add)
                    }
                    line.startsWith("$privateChatPrefix\t") -> {
                        decodePrivateChat(line)?.let(privateChats::add)
                    }
                }
            }

        return PersistedAuroraState(
            contacts = contacts.sortedWith(
                compareBy<PersistedContact>({ it.displayName.lowercase() }, { it.canonicalPeerId })
            ),
            messages = messages.sortedWith(
                compareBy<PersistedChatMessage>({ it.createdAtMillis }, { it.messageId })
            ),
            privateChats = privateChats.sortedWith(
                compareBy<PersistedPrivateChat>({ it.lastUpdatedMillis }, { it.canonicalPeerId })
            )
        )
    }

    private fun encodeState(state: PersistedAuroraState): String {
        val lines = buildList {
            add(fileFormatHeader)
            state.contacts.forEach { contact ->
                add(
                    listOf(
                        contactPrefix,
                        encodeToken(contact.canonicalPeerId),
                        encodeToken(contact.displayName),
                        contact.createdAtMillis.toString(),
                        contact.lastSeenMillis?.toString().orEmpty()
                    ).joinToString(separator = "\t")
                )
            }
            state.messages.forEach { message ->
                add(
                    listOf(
                        messagePrefix,
                        encodeToken(message.messageId),
                        message.threadType.name,
                        message.peerId?.let(::encodeToken).orEmpty(),
                        encodeToken(message.text),
                        message.createdAtMillis.toString(),
                        message.direction.name,
                        message.status.name,
                        encodeToken(message.senderId),
                        encodeToken(message.senderName)
                    ).joinToString(separator = "\t")
                )
            }
            state.privateChats.forEach { privateChat ->
                add(
                    listOf(
                        privateChatPrefix,
                        encodeToken(privateChat.canonicalPeerId),
                        privateChat.privateChatId?.let(::encodeToken).orEmpty(),
                        privateChat.localProposalId?.let(::encodeToken).orEmpty(),
                        privateChat.remoteProposalId?.let(::encodeToken).orEmpty(),
                        privateChat.customChatName?.let(::encodeToken).orEmpty(),
                        privateChat.lastKnownRemoteUsername?.let(::encodeToken).orEmpty(),
                        privateChat.createdAtMillis.toString(),
                        privateChat.lastUpdatedMillis.toString()
                    ).joinToString(separator = "\t")
                )
            }
        }

        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun decodeContact(line: String): PersistedContact? {
        val parts = line.split('\t')
        if (parts.size != expectedContactPartCount) {
            return null
        }

        return runCatching {
            PersistedContact(
                canonicalPeerId = decodeToken(parts[1]),
                displayName = decodeToken(parts[2]),
                createdAtMillis = parts[3].toLong(),
                lastSeenMillis = parts[4].takeIf { it.isNotEmpty() }?.toLong()
            )
        }.getOrNull()
    }

    private fun decodeMessage(line: String): PersistedChatMessage? {
        val parts = line.split('\t')
        if (parts.size != expectedMessagePartCount) {
            return null
        }

        return runCatching {
            PersistedChatMessage(
                messageId = decodeToken(parts[1]),
                threadType = PersistedChatThreadType.valueOf(parts[2]),
                peerId = parts[3].takeIf { it.isNotEmpty() }?.let(::decodeToken),
                text = decodeToken(parts[4]),
                createdAtMillis = parts[5].toLong(),
                direction = PersistedMessageDirection.valueOf(parts[6]),
                status = gr.hua.aurora.model.MessageStatus.valueOf(parts[7]),
                senderId = decodeToken(parts[8]),
                senderName = decodeToken(parts[9])
            )
        }.getOrNull()
    }

    private fun decodePrivateChat(line: String): PersistedPrivateChat? {
        val parts = line.split('\t')
        if (parts.size != expectedPrivateChatPartCount) {
            return null
        }

        return runCatching {
            PersistedPrivateChat(
                canonicalPeerId = decodeToken(parts[1]),
                privateChatId = parts[2].takeIf { it.isNotEmpty() }?.let(::decodeToken),
                localProposalId = parts[3].takeIf { it.isNotEmpty() }?.let(::decodeToken),
                remoteProposalId = parts[4].takeIf { it.isNotEmpty() }?.let(::decodeToken),
                customChatName = parts[5].takeIf { it.isNotEmpty() }?.let(::decodeToken),
                lastKnownRemoteUsername = parts[6].takeIf { it.isNotEmpty() }?.let(::decodeToken),
                createdAtMillis = parts[7].toLong(),
                lastUpdatedMillis = parts[8].toLong()
            )
        }.getOrNull()
    }

    private fun encodeToken(value: String): String {
        return encodeHex(value.toByteArray(UTF_8))
    }

    private fun decodeToken(value: String): String {
        return String(decodeHex(value), UTF_8)
    }

    private fun encodeHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = hexDigits[value ushr 4]
            chars[index * 2 + 1] = hexDigits[value and 0x0F]
        }
        return String(chars)
    }

    private fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0) {
            "Encoded token must have an even number of characters."
        }

        return ByteArray(value.length / 2) { index ->
            val first = decodeHexDigit(value[index * 2])
            val second = decodeHexDigit(value[index * 2 + 1])
            ((first shl 4) or second).toByte()
        }
    }

    private fun decodeHexDigit(char: Char): Int {
        return when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> throw IllegalArgumentException("Invalid hex digit: $char")
        }
    }

    companion object {
        private const val stateFileName = "aurora_state_store_v1.txt"
        private const val fileFormatHeader = "AURORA_STATE_V1"
        private const val contactPrefix = "CONTACT"
        private const val messagePrefix = "MESSAGE"
        private const val privateChatPrefix = "PRIVATE_CHAT"
        private const val expectedContactPartCount = 5
        private const val expectedMessagePartCount = 10
        private const val expectedPrivateChatPartCount = 9
        private val hexDigits = "0123456789abcdef".toCharArray()
    }
}
