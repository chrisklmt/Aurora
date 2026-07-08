package gr.hua.aurora.transport.hybrid

data class HybridBootstrapDiagnostics(
    val candidateCount: Int,
    val socketReadyCandidateCount: Int,
    val selectionStatus: SelectionStatus,
    val selectedPeerId: String?,
    val selectedSessionId: String?,
    val selectedGroupOwnerAddress: String?,
    val selectedSocketPort: Int?,
    val selectedLatestCreatedAtMillis: Long?,
    val statusText: String
) {
    init {
        require(candidateCount >= 0) {
            "Hybrid bootstrap diagnostics candidateCount must be non-negative."
        }
        require(socketReadyCandidateCount >= 0) {
            "Hybrid bootstrap diagnostics socketReadyCandidateCount must be non-negative."
        }
        require(socketReadyCandidateCount <= candidateCount) {
            "Hybrid bootstrap diagnostics socketReadyCandidateCount cannot exceed candidateCount."
        }
        require(statusText.isNotBlank()) {
            "Hybrid bootstrap diagnostics statusText must not be blank."
        }
    }

    enum class SelectionStatus {
        NoCandidates,
        NoSocketReadyCandidates,
        Selected
    }
}
