package gr.hua.aurora.transport.hybrid

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object HybridTransportControlCodec {
    internal const val currentProtocolVersion: Int = 1

    private const val formatMagic = "AURORA_HYBRID_CONTROL"
    private const val separator = "|"
    private const val nullToken = "~"
    private const val legacyPartCount = 13
    private const val legacyExtendedPartCount = 17
    private const val extendedPartCount = 18
    private const val diagnosticsExtendedPartCount = 21
    private const val diagnosticsProbePayloadPartCount = 22
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(
        message: HybridTransportControlMessage
    ): String {
        require(message.protocolVersion == currentProtocolVersion) {
            "Unsupported hybrid transport protocol version: ${message.protocolVersion}."
        }

        val parts = mutableListOf(
            formatMagic,
            message.protocolVersion.toString(),
            message.messageType.name,
            encodeField(message.sessionId),
            message.publicPeerIdHint?.let(::encodeField) ?: nullToken,
            message.relatedPeerIdHint?.let(::encodeField) ?: nullToken,
            message.groupOwnerAddress?.let(::encodeField) ?: nullToken,
            message.socketPort?.toString() ?: nullToken,
            message.createdAtMillis.toString(),
            message.associatedSessionId?.let(::encodeField) ?: nullToken,
            message.expiresAtMillis?.toString() ?: nullToken,
            message.generationToken?.toString() ?: nullToken,
            encodeCapabilityFlags(message.capabilityFlags)
        )
        val hasExtendedPeerFields =
            message.senderPeerIdHint != null ||
            message.expectedPeerIdHint != null ||
            message.wifiDirectCorrelationToken != null ||
            message.wifiDirectDeviceAddress != null ||
            message.wifiDirectDeviceName != null
        val hasDiagnosticsPhaseFields =
            message.diagnosticsStepNumber != null ||
                message.diagnosticsPhaseState != null ||
                message.diagnosticsAttemptNumber != null ||
                message.diagnosticsApplicationProbePayload != null
        if (hasExtendedPeerFields || hasDiagnosticsPhaseFields) {
            parts += listOf(
                message.senderPeerIdHint?.let(::encodeField) ?: nullToken,
                message.expectedPeerIdHint?.let(::encodeField) ?: nullToken,
                message.wifiDirectCorrelationToken?.let(::encodeField) ?: nullToken,
                message.wifiDirectDeviceAddress?.let(::encodeField) ?: nullToken,
                message.wifiDirectDeviceName?.let(::encodeField) ?: nullToken
            )
            if (hasDiagnosticsPhaseFields) {
                parts += listOf(
                    message.diagnosticsStepNumber?.toString() ?: nullToken,
                    message.diagnosticsPhaseState?.let(::encodeField) ?: nullToken,
                    message.diagnosticsAttemptNumber?.toString() ?: nullToken,
                    message.diagnosticsApplicationProbePayload?.let(::encodeField) ?: nullToken
                )
            }
        }
        return parts.joinToString(separator)
    }

    fun decode(
        encoded: String
    ): HybridTransportControlMessage {
        val parts = encoded.split(separator)
        require(
            parts.size == legacyPartCount ||
                parts.size == legacyExtendedPartCount ||
                parts.size == extendedPartCount ||
                parts.size == diagnosticsExtendedPartCount ||
                parts.size == diagnosticsProbePayloadPartCount
        ) {
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

        val socketPort = decodeNullablePort(parts[7])
        val createdAtMillis = parts[8].toLongOrNull()
            ?: throw IllegalArgumentException(
                "Invalid hybrid transport createdAtMillis: ${parts[8]}."
            )
        val expiresAtMillis = decodeNullableLong(parts[10], "expiresAtMillis")
        val generationToken = decodeNullableLong(parts[11], "generationToken")

        return HybridTransportControlMessage(
            protocolVersion = protocolVersion,
            messageType = messageType,
            sessionId = decodeField(parts[3], "sessionId"),
            publicPeerIdHint = decodeNullableField(parts[4], "publicPeerIdHint"),
            relatedPeerIdHint = decodeNullableField(parts[5], "relatedPeerIdHint"),
            senderPeerIdHint = decodeExtendedNullableField(parts, 13, "senderPeerIdHint"),
            expectedPeerIdHint = decodeExtendedNullableField(parts, 14, "expectedPeerIdHint"),
            wifiDirectCorrelationToken = if (parts.size >= extendedPartCount) {
                decodeNullableField(parts[15], "wifiDirectCorrelationToken")
            } else {
                null
            },
            wifiDirectDeviceAddress = when (parts.size) {
                legacyExtendedPartCount -> decodeNullableField(
                    parts[15],
                    "wifiDirectDeviceAddress"
                )
                else -> decodeExtendedNullableField(
                    parts = parts,
                    index = 16,
                    fieldName = "wifiDirectDeviceAddress"
                )
            },
            wifiDirectDeviceName = when (parts.size) {
                legacyExtendedPartCount -> decodeNullableField(
                    parts[16],
                    "wifiDirectDeviceName"
                )
                else -> decodeExtendedNullableField(
                    parts = parts,
                    index = 17,
                    fieldName = "wifiDirectDeviceName"
                )
            },
            groupOwnerAddress = decodeNullableField(parts[6], "groupOwnerAddress"),
            socketPort = socketPort,
            diagnosticsStepNumber = decodeExtendedNullableInt(
                parts = parts,
                index = 18,
                fieldName = "diagnosticsStepNumber"
            ),
            diagnosticsPhaseState = decodeExtendedNullableField(
                parts = parts,
                index = 19,
                fieldName = "diagnosticsPhaseState"
            ),
            diagnosticsAttemptNumber = decodeExtendedNullableInt(
                parts = parts,
                index = 20,
                fieldName = "diagnosticsAttemptNumber"
            ),
            diagnosticsApplicationProbePayload = decodeExtendedNullableField(
                parts = parts,
                index = 21,
                fieldName = "diagnosticsApplicationProbePayload"
            ),
            createdAtMillis = createdAtMillis,
            associatedSessionId = decodeNullableField(parts[9], "associatedSessionId"),
            expiresAtMillis = expiresAtMillis,
            generationToken = generationToken,
            capabilityFlags = decodeCapabilityFlags(parts[12])
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

    private fun decodeExtendedNullableField(
        parts: List<String>,
        index: Int,
        fieldName: String
    ): String? {
        return if (parts.size <= index) {
            null
        } else {
            decodeNullableField(parts[index], fieldName)
        }
    }

    private fun decodeExtendedNullableInt(
        parts: List<String>,
        index: Int,
        fieldName: String
    ): Int? {
        if (parts.size <= index) {
            return null
        }
        val token = parts[index]
        if (token == nullToken) {
            return null
        }
        return token.toIntOrNull()
            ?: throw IllegalArgumentException(
                "Invalid hybrid transport $fieldName: $token."
            )
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

    private fun decodeNullableLong(
        token: String,
        fieldName: String
    ): Long? {
        if (token == nullToken) {
            return null
        }

        return token.toLongOrNull()
            ?: throw IllegalArgumentException(
                "Invalid hybrid transport $fieldName: $token."
            )
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
