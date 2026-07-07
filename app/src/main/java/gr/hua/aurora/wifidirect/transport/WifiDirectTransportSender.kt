package gr.hua.aurora.wifidirect.transport

import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrame

internal interface WifiDirectTransportSender {
    suspend fun send(
        frame: WifiDirectTransportFrame
    ): WifiDirectTransportSendResult
}
