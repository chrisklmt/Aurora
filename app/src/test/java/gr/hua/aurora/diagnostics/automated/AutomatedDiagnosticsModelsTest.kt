package gr.hua.aurora.diagnostics.automated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomatedDiagnosticsModelsTest {
    @Test
    fun compactSummaryTextReflectsOverallStatus() {
        val initial = AutomatedDiagnosticsRunState.initial()

        assertEquals("Idle", automatedDiagnosticsCompactSummaryText(initial))
        assertEquals(
            "Running step 3/${AutomatedDiagnosticStepId.entries.size}",
            automatedDiagnosticsCompactSummaryText(
                initial.copy(
                    overallStatus = AutomatedDiagnosticsOverallStatus.RUNNING,
                    currentStepNumber = 3
                )
            )
        )
        assertEquals(
            "Pass (${AutomatedDiagnosticStepId.entries.size}/${AutomatedDiagnosticStepId.entries.size})",
            automatedDiagnosticsCompactSummaryText(
                initial.copy(
                    overallStatus = AutomatedDiagnosticsOverallStatus.PASS,
                    passedCount = AutomatedDiagnosticStepId.entries.size
                )
            )
        )
        assertEquals(
            "Blocked (2 blocked)",
            automatedDiagnosticsCompactSummaryText(
                initial.copy(
                    overallStatus = AutomatedDiagnosticsOverallStatus.BLOCKED,
                    blockedCount = 2
                )
            )
        )
    }

    @Test
    fun autoExpandKeepsRunningBlockedAndFailedRowsOpen() {
        assertTrue(
            automatedDiagnosticsShouldAutoExpand(
                AutomatedDiagnosticStepResult(
                    stepId = AutomatedDiagnosticStepId.BLE_RUNTIME,
                    status = AutomatedDiagnosticStepStatus.RUNNING
                )
            )
        )
        assertTrue(
            automatedDiagnosticsShouldAutoExpand(
                AutomatedDiagnosticStepResult(
                    stepId = AutomatedDiagnosticStepId.BLE_RUNTIME,
                    status = AutomatedDiagnosticStepStatus.FAIL
                )
            )
        )
        assertTrue(
            automatedDiagnosticsShouldAutoExpand(
                AutomatedDiagnosticStepResult(
                    stepId = AutomatedDiagnosticStepId.BLE_RUNTIME,
                    status = AutomatedDiagnosticStepStatus.BLOCKED
                )
            )
        )
        assertFalse(
            automatedDiagnosticsShouldAutoExpand(
                AutomatedDiagnosticStepResult(
                    stepId = AutomatedDiagnosticStepId.BLE_RUNTIME,
                    status = AutomatedDiagnosticStepStatus.PASS
                )
            )
        )
    }

    @Test
    fun plainTextReportIncludesEvidenceAndPhaseTwoSummary() {
        val step = AutomatedDiagnosticStepResult(
            stepId = AutomatedDiagnosticStepId.PREFLIGHT,
            status = AutomatedDiagnosticStepStatus.PASS,
            summary = "Device preflight passed",
            evidenceValues = listOf(
                AutomatedDiagnosticEvidenceValue("Bluetooth", "enabled")
            ),
            waitingProgressText = "Waiting 0.100s / 2.000s"
        )

        val report = automatedDiagnosticsPlainTextReport(
            overallStatus = AutomatedDiagnosticsOverallStatus.PASS,
            selectedPeerId = "peer-123",
            localPeerRole = AutomatedDiagnosticsPeerRole.COORDINATOR,
            elapsedMillis = 2_500L,
            steps = listOf(step) + AutomatedDiagnosticStepId.entries.drop(1).map {
                AutomatedDiagnosticStepResult(it)
            },
            phaseTwoSummary = automatedDiagnosticsPhaseTwoSummary
        )

        assertTrue(report.contains("Automated Aurora Test"))
        assertTrue(report.contains("Bluetooth: enabled"))
        assertTrue(report.contains(automatedDiagnosticsPhaseTwoSummary))
    }
}
