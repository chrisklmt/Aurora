package gr.hua.aurora.transport.hybrid

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object HybridTransportControlCodec {
    internal const val currentProtocolVersion: Int = 1

    private const val formatMagic = "AURORA_HYBRID_CONTROL"
    private const val separator = "|"
    private const val nullToken = "~"
    private const val expectedPartCount = 9
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(
        message: HybridTransportControlMessage
    ): String {
        require(message.protocolVersion == currentProtocolVersion) {
            "Unsupported hybrid transport protocol version: ${message.protocolVersion}."
        }

        return listOf(
            formatMagic,
            message.protocolVersion.toString(),
            message.messageType.name,
            encodeField(message.sessionId),
            message.publicPeerIdHint?.let(::encodeField) ?: nullToken,
            message.groupOwnerAddress?.let(::encodeField) ?: nullToken,
            message.socketPort?.toString() ?: nullToken,
            message.createdAtMillis.toString(),
            encodeCapabilityFlags(message.capabilityFlags)
        ).joinToString(separator)
    }

    fun decode(
        encoded: String
    ): HybridTransportControlMessage {
        val parts = encoded.split(separator, limit = expectedPartCount)
        require(parts.size == expectedPartCount) {
            "Invalid hybrid transport control part count: ${parts.size}."
        }
        require(parts[0] == formatMagic) {
            "Unsupported hybrid transport control format: ${parts[0]}."
        }

        val protocolVersion = parts[1].toIntOrNull()
            ?: throw IllegalArgumentException(
                "Invalid hybrid transport protocol version: ${parts[1]}."
            )
        require(protocolVersion == currentProtocolVersion) {
            "Unsupported hybrid transport protocol version: $protocolVersion."
        }

        val messageType = runCatching {
            HybridTransportControlMessage.MessageType.valueOf(parts[2])
        }.getOrElse { error ->
            throw IllegalArgumentException(
                "Unknown hybrid transport message type: ${parts[2]}.",
                error
            )
        }

        val socketPort = decodeNullablePort(parts[6])
        val createdAtMillis = parts[7].toLongOrNull()
            ?: throw IllegalArgumentException(
                "Invalid hybrid transport createdAtMillis: ${parts[7]}."
            )

        return HybridTransportControlMessage(
            protocolVersion = protocolVersion,
            messageType = messageType,
            sessionId = decodeField(parts[3], "sessionId"),
            publicPeerIdHint = decodeNullableField(parts[4], "publicPeerIdHint"),
            groupOwnerAddress = decodeNullableField(parts[5], "groupOwnerAddress"),
            socketPort = socketPort,
            createdAtMillis = createdAtMillis,
            capabilityFlags = decodeCapabilityFlags(parts[8])
        )
    }

    fun decodeOrNull(
        encoded: String
    ): HybridTransportControlMessage? {
        return runCatching { decode(encoded) }.getOrNull()
    }

    private fun encodeField(
        value: String
    ): String {
        return encoder.encodeToString(value.toByteArray(UTF_8))
    }

    private fun decodeNullableField(
        token: String,
        fieldName: String
    ): String? {
        return if (token == nullToken) {
            null
        } else {
            decodeField(token, fieldName)
        }
    }

    private fun decodeField(
        token: String,
        fieldName: String
    ): String {
        return try {
            String(decoder.decode(token), UTF_8)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid hybrid transport field encoding for $fieldName.",
                error
            )
        }
    }

    private fun decodeNullablePort(
        token: String
    ): Int? {
        if (token == nullToken) {
            return null
        }

        val port = token.toIntOrNull()
            ?: throw IllegalArgumentException(
                "Invalid hybrid transport socket port: $token."
            )
        require(port in 1..65535) {
            "Invalid hybrid transport socket port: $token."
        }
        return port
    }

    private fun encodeCapabilityFlags(
        capabilityFlags: Set<HybridTransportControlMessage.CapabilityFlag>
    ): String {
        if (capabilityFlags.isEmpty()) {
            return nullToken
        }

        return capabilityFlags
            .map(HybridTransportControlMessage.CapabilityFlag::name)
            .sorted()
            .joinToString(",")
    }

    private fun decodeCapabilityFlags(
        token: String
    ): Set<HybridTransportControlMessage.CapabilityFlag> {
        if (token == nullToken) {
            return emptySet()
        }

        return token.split(",")
            .map { capabilityName ->
                runCatching {
                    HybridTransportControlMessage.CapabilityFlag.valueOf(capabilityName)
                }.getOrElse { error ->
                    throw IllegalArgumentException(
                        "Unknown hybrid transport capability flag: $capabilityName.",
                        error
                    )
                }
            }
            .toSet()
    }
}
