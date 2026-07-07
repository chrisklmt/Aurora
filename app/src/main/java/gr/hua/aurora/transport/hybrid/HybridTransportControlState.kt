package gr.hua.aurora.transport.hybrid

internal data class HybridTransportControlSessionState(
    val latestOffer: HybridTransportControlMessage? = null,
    val latestAccept: HybridTransportControlMessage? = null,
    val latestSocketHint: HybridTransportControlMessage? = null
)

internal data class HybridTransportControlState(
    val sessionsByPeerId: Map<String, Map<String, HybridTransportControlSessionState>> = emptyMap()
) {
    fun sessionStateFor(
        peerId: String,
        sessionId: String
    ): HybridTransportControlSessionState? {
        return sessionsByPeerId[peerId]?.get(sessionId)
    }
}
