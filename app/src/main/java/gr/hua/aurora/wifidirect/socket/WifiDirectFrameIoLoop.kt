package gr.hua.aurora.wifidirect.socket

import gr.hua.aurora.wifidirect.frame.WifiDirectFrame
import gr.hua.aurora.wifidirect.frame.WifiDirectFrameCodec
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

internal data class WifiDirectIncomingFrame(
    val frame: WifiDirectFrame,
    val frameByteCount: Long
)

internal class WifiDirectFrameIoLoop(
    private val frameCodec: WifiDirectFrameCodec = WifiDirectFrameCodec()
) {
    fun readUntilClosed(
        socket: Socket,
        isActive: () -> Boolean,
        onIncomingFrame: (WifiDirectIncomingFrame) -> Unit,
        onClosed: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        readUntilClosed(
            inputStream = socket.getInputStream(),
            isActive = isActive,
            onIncomingFrame = onIncomingFrame,
            onClosed = onClosed,
            onFailure = onFailure
        )
    }

    fun readUntilClosed(
        inputStream: InputStream,
        isActive: () -> Boolean,
        onIncomingFrame: (WifiDirectIncomingFrame) -> Unit,
        onClosed: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val decoder = frameCodec.newDecoder()
        val buffer = ByteArray(1_024)
        while (isActive()) {
            val readCount = runCatching {
                inputStream.read(buffer)
            }.getOrElse { error ->
                onFailure(error)
                return
            }
            if (readCount < 0) {
                decoder.finish()
                    .onSuccess { onClosed() }
                    .onFailure(onFailure)
                return
            }
            if (readCount == 0) {
                continue
            }
            val incomingBytes = buffer.copyOf(readCount)
            decoder.append(incomingBytes)
                .onSuccess { frames ->
                    frames.forEach { frame ->
                        onIncomingFrame(
                            WifiDirectIncomingFrame(
                                frame = frame,
                                frameByteCount = Int.SIZE_BYTES + frame.payloadSize.toLong()
                            )
                        )
                    }
                }
                .onFailure(onFailure)
                .getOrNull() ?: return
        }
    }

    fun writeFrame(
        socket: Socket,
        frame: WifiDirectFrame
    ): Result<Long> {
        return writeFrame(
            outputStream = socket.getOutputStream(),
            frame = frame
        )
    }

    fun writeFrame(
        outputStream: OutputStream,
        frame: WifiDirectFrame
    ): Result<Long> {
        return runCatching {
            val encoded = frameCodec.encode(frame)
            outputStream.write(encoded)
            outputStream.flush()
            encoded.size.toLong()
        }
    }
}
