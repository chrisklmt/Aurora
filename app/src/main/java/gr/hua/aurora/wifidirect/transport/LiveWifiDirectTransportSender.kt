package gr.hua.aurora.wifidirect.transport

import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapter
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrame
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class LiveWifiDirectTransportSender(
    private val transportAdapter: WifiDirectTransportAdapter
) : WifiDirectTransportSender {
    override suspend fun send(
        frame: WifiDirectTransportFrame
    ): WifiDirectTransportSendResult {
        notReadyResultOrNull(
            diagnostics = transportAdapter.currentDiagnostics()
        )?.let { notReadyResult ->
            return notReadyResult
        }

        return suspendCoroutine { continuation ->
            transportAdapter.submit(frame) { result ->
                result.onSuccess {
                    continuation.resume(WifiDirectTransportSendResult.Success)
                }.onFailure { error ->
                    continuation.resume(
                        notReadyResultOrNull(
                            diagnostics = transportAdapter.currentDiagnostics(),
                            fallbackReason = safeErrorDetail(error)
                        ) ?: WifiDirectTransportSendResult.Failed(
                            reason = safeErrorDetail(error),
                            cause = error
                        )
                    )
                }
            }
        }
    }

    private fun notReadyResultOrNull(
        diagnostics: WifiDirectTransportAdapterDiagnostics,
        fallbackReason: String? = null
    ): WifiDirectTransportSendResult.NotReady? {
        return when (diagnostics.state) {
            WifiDirectTransportAdapterState.DISABLED -> WifiDirectTransportSendResult.NotReady(
                reason = fallbackReason
                    ?: diagnostics.notReadyReason
                    ?: "Wi-Fi Direct transport adapter disabled."
            )
            WifiDirectTransportAdapterState.NOT_READY -> WifiDirectTransportSendResult.NotReady(
                reason = fallbackReason
                    ?: diagnostics.notReadyReason
                    ?: "Wi-Fi Direct transport adapter not ready."
            )
            WifiDirectTransportAdapterState.READY,
            WifiDirectTransportAdapterState.FAILED -> null
        }
    }

    private fun safeErrorDetail(
        error: Throwable
    ): String {
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: error::class.java.simpleName
    }
}
