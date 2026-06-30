package gr.hua.aurora.wifidirect

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets

private const val wifiDirectDebugPingMessage = "ping"
private const val wifiDirectDebugPongMessage = "pong"

internal data class WifiDirectSocketIncomingMessage(
    val text: String,
    val byteCount: Long,
    val autoReply: String? = null
)

internal class WifiDirectSocketIoLoop {
    fun readUntilClosed(
        socket: Socket,
        isActive: () -> Boolean,
        onIncomingMessage: (WifiDirectSocketIncomingMessage) -> Unit,
        onClosed: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        )
        while (isActive()) {
            val line = runCatching {
                reader.readLine()
            }.getOrElse { error ->
                onFailure(error)
                return
            }
            if (line == null) {
                onClosed()
                return
            }
            onIncomingMessage(
                WifiDirectSocketIncomingMessage(
                    text = line,
                    byteCount = messageBytes(line).size.toLong(),
                    autoReply = if (line == wifiDirectDebugPingMessage) {
                        wifiDirectDebugPongMessage
                    } else {
                        null
                    }
                )
            )
        }
    }

    fun writeLine(
        socket: Socket,
        message: String
    ): Result<Long> {
        val bytes = messageBytes(message)
        return runCatching {
            val writer = BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            )
            writer.write(message)
            writer.newLine()
            writer.flush()
            bytes.size.toLong()
        }
    }

    private fun messageBytes(
        message: String
    ): ByteArray {
        return "$message\n".toByteArray(StandardCharsets.UTF_8)
    }
}
