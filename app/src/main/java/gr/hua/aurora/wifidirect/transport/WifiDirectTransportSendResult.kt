package gr.hua.aurora.wifidirect.transport

internal sealed interface WifiDirectTransportSendResult {
    data object Success : WifiDirectTransportSendResult

    data class NotReady(
        val reason: String
    ) : WifiDirectTransportSendResult {
        init {
            require(reason.isNotBlank()) {
                "Wi-Fi Direct transport not-ready reason must not be blank."
            }
        }
    }

    data class Failed(
        val reason: String,
        val cause: Throwable? = null
    ) : WifiDirectTransportSendResult {
        init {
            require(reason.isNotBlank()) {
                "Wi-Fi Direct transport failure reason must not be blank."
            }
        }
    }
}
