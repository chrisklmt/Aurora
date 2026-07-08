package gr.hua.aurora.transport.hybrid

object HybridBootstrapDiagnosticsFormatter {
    fun format(
        decision: HybridBootstrapDecision
    ): HybridBootstrapDiagnostics {
        val candidates = decision.candidates
        val socketReadyCandidateCount = candidates.count(HybridBootstrapCandidate::socketReady)

        return when (val selection = decision.selection) {
            HybridBootstrapCandidateSelection.NoCandidates -> {
                HybridBootstrapDiagnostics(
                    candidateCount = candidates.size,
                    socketReadyCandidateCount = socketReadyCandidateCount,
                    selectionStatus = HybridBootstrapDiagnostics.SelectionStatus.NoCandidates,
                    selectedPeerId = null,
                    selectedSessionId = null,
                    selectedGroupOwnerAddress = null,
                    selectedSocketPort = null,
                    selectedLatestCreatedAtMillis = null,
                    statusText = "No hybrid bootstrap candidates"
                )
            }

            HybridBootstrapCandidateSelection.NoSocketReadyCandidates -> {
                HybridBootstrapDiagnostics(
                    candidateCount = candidates.size,
                    socketReadyCandidateCount = socketReadyCandidateCount,
                    selectionStatus = HybridBootstrapDiagnostics.SelectionStatus.NoSocketReadyCandidates,
                    selectedPeerId = null,
                    selectedSessionId = null,
                    selectedGroupOwnerAddress = null,
                    selectedSocketPort = null,
                    selectedLatestCreatedAtMillis = null,
                    statusText = "Hybrid bootstrap candidates available, none socket-ready"
                )
            }

            is HybridBootstrapCandidateSelection.Selected -> {
                val candidate = selection.candidate
                HybridBootstrapDiagnostics(
                    candidateCount = candidates.size,
                    socketReadyCandidateCount = socketReadyCandidateCount,
                    selectionStatus = HybridBootstrapDiagnostics.SelectionStatus.Selected,
                    selectedPeerId = candidate.peerId,
                    selectedSessionId = candidate.sessionId,
                    selectedGroupOwnerAddress = candidate.groupOwnerAddress,
                    selectedSocketPort = candidate.socketPort,
                    selectedLatestCreatedAtMillis = candidate.latestCreatedAtMillis,
                    statusText =
                        "Hybrid bootstrap candidate ready: peer=${candidate.peerId} " +
                            "session=${candidate.sessionId} " +
                            "address=${candidate.groupOwnerAddress} " +
                            "port=${candidate.socketPort}"
                )
            }
        }
    }
}
