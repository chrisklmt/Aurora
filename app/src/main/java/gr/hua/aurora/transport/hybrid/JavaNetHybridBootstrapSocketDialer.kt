package gr.hua.aurora.transport.hybrid

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal const val hybridBootstrapSocketDialMaxConnectTimeoutMillis = 30_000L

internal fun hybridBootstrapSocketDialInvalidAddressReason(): String {
    return "Hybrid bootstrap socket dial address must not be blank."
}

internal fun hybridBootstrapSocketDialInvalidPortReason(): String {
    return "Hybrid bootstrap socket dial port must be in 1..65535."
}

internal fun hybridBootstrapSocketDialInvalidTimeoutReason(): String {
    return "Hybrid bootstrap socket dial connectTimeoutMillis must be in 1..30000."
}

internal fun hybridBootstrapSocketDialConnectTimeoutMillisOrNull(
    connectTimeoutMillis: Long
): Int? {
    if (connectTimeoutMillis !in 1L..hybridBootstrapSocketDialMaxConnectTimeoutMillis) {
        return null
    }

    return connectTimeoutMillis.toInt()
}

class JavaNetHybridBootstrapSocketDialer(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val socketFactory: () -> Socket = ::Socket
) : HybridBootstrapSocketDialer {
    override fun dial(
        address: String,
        port: Int,
        connectTimeoutMillis: Long
    ): HybridBootstrapSocketDialResult {
        if (address.isBlank()) {
            return HybridBootstrapSocketDialResult.Failed(
                reason = hybridBootstrapSocketDialInvalidAddressReason()
            )
        }
        if (port !in 1..65_535) {
            return HybridBootstrapSocketDialResult.Failed(
                reason = hybridBootstrapSocketDialInvalidPortReason()
            )
        }
        val normalizedTimeoutMillis = hybridBootstrapSocketDialConnectTimeoutMillisOrNull(
            connectTimeoutMillis = connectTimeoutMillis
        ) ?: return HybridBootstrapSocketDialResult.Failed(
            reason = hybridBootstrapSocketDialInvalidTimeoutReason()
        )

        return try {
            socketFactory().use { socket ->
                socket.connect(
                    InetSocketAddress(address, port),
                    normalizedTimeoutMillis
                )
            }
            HybridBootstrapSocketDialResult.Connected(
                address = address,
                port = port,
                connectedAtMillis = nowMillis()
            )
        } catch (_: SocketTimeoutException) {
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial failed: SocketTimeoutException."
            )
        } catch (_: ConnectException) {
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial failed: ConnectException."
            )
        } catch (_: UnknownHostException) {
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial failed: UnknownHostException."
            )
        } catch (_: SecurityException) {
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial failed: SecurityException."
            )
        } catch (_: IOException) {
            HybridBootstrapSocketDialResult.Failed(
                reason = "Hybrid bootstrap socket dial failed: IOException."
            )
        }
    }
}
