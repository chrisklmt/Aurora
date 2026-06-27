package gr.hua.aurora.model

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom

data class PrivateChatIdentity(
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
            "Private chat canonicalPeerId must not be blank."
        }
        require(createdAtMillis >= 0L) {
            "Private chat createdAtMillis must be non-negative."
        }
        require(lastUpdatedMillis >= 0L) {
            "Private chat lastUpdatedMillis must be non-negative."
        }
        require(privateChatId?.isNotBlank() != false) {
            "Private chat id must not be blank when present."
        }
        require(localProposalId?.isNotBlank() != false) {
            "Private chat localProposalId must not be blank when present."
        }
        require(remoteProposalId?.isNotBlank() != false) {
            "Private chat remoteProposalId must not be blank when present."
        }
        require(customChatName?.isNotBlank() != false) {
            "Private chat customChatName must not be blank when present."
        }
        require(lastKnownRemoteUsername?.isNotBlank() != false) {
            "Private chat lastKnownRemoteUsername must not be blank when present."
        }
    }

    val isEstablished: Boolean
        get() = privateChatId != null

    fun displayNameOrNull(): String? {
        return customChatName?.takeIf { it.isNotBlank() }
            ?: lastKnownRemoteUsername?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val proposalByteLength = 16
        private val secureRandom = SecureRandom()

        fun generateProposalId(): String {
            val bytes = ByteArray(proposalByteLength)
            secureRandom.nextBytes(bytes)
            return bytes.toHexString()
        }

        fun deriveSharedChatId(
            localProposalId: String,
            remoteProposalId: String
        ): String {
            val local = localProposalId.trim()
            val remote = remoteProposalId.trim()
            require(local.isNotEmpty()) {
                "Private chat localProposalId must not be blank."
            }
            require(remote.isNotEmpty()) {
                "Private chat remoteProposalId must not be blank."
            }

            val ordered = listOf(local, remote).sorted()
            val digest = MessageDigest.getInstance("SHA-256").digest(
                "AURORA_PRIVATE_CHAT_ID_V1|${ordered[0]}|${ordered[1]}".toByteArray(UTF_8)
            )
            return digest.copyOfRange(0, proposalByteLength).toHexString()
        }

        private fun ByteArray.toHexString(): String {
            return joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
        }
    }
}
