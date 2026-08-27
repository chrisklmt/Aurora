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
    fun plainTextReportIncludesEvidenceAndOptionalFooterWhenProvided() {
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
            phaseTwoSummary = "Custom diagnostics footer"
        )

        assertTrue(report.contains("Automated Aurora Test"))
        assertTrue(report.contains("Bluetooth: enabled"))
        assertTrue(report.contains("Custom diagnostics footer"))
    }

    @Test
    fun phaseSectionsPartitionStepsIntoExpectedRangesExactlyOnce() {
        val state = AutomatedDiagnosticsRunState.initial()

        val phaseSections = automatedDiagnosticsPhaseSections(state)

        assertEquals(
            listOf(
                AutomatedDiagnosticsPhase.PHASE_1,
                AutomatedDiagnosticsPhase.PHASE_2,
                AutomatedDiagnosticsPhase.PHASE_3
            ),
            phaseSections.map { it.phase }
        )
        assertEquals((1..10).toList(), phaseSections[0].steps.map { it.visibleStepNumber })
        assertEquals((11..17).toList(), phaseSections[1].steps.map { it.visibleStepNumber })
        assertEquals((18..21).toList(), phaseSections[2].steps.map { it.visibleStepNumber })
        assertEquals(
            AutomatedDiagnosticStepId.entries,
            phaseSections.flatMap { section -> section.steps.map { it.stepId } }
        )
    }

    @Test
    fun phaseStatusAggregationMatchesPriorityRules() {
        assertEquals(
            AutomatedDiagnosticStepStatus.PASS,
            automatedDiagnosticsPhaseStatus(
                listOf(
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.PREFLIGHT,
                        status = AutomatedDiagnosticStepStatus.PASS
                    ),
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.BLE_RUNTIME,
                        status = AutomatedDiagnosticStepStatus.PASS
                    )
                )
            )
        )
        assertEquals(
            AutomatedDiagnosticStepStatus.BLOCKED,
            automatedDiagnosticsPhaseStatus(
                listOf(
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.PREFLIGHT,
                        status = AutomatedDiagnosticStepStatus.PASS
                    ),
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.BLE_RUNTIME,
                        status = AutomatedDiagnosticStepStatus.BLOCKED
                    )
                )
            )
        )
        assertEquals(
            AutomatedDiagnosticStepStatus.FAIL,
            automatedDiagnosticsPhaseStatus(
                listOf(
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.PREFLIGHT,
                        status = AutomatedDiagnosticStepStatus.RUNNING
                    ),
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.BLE_RUNTIME,
                        status = AutomatedDiagnosticStepStatus.FAIL
                    )
                )
            )
        )
        assertEquals(
            AutomatedDiagnosticStepStatus.RUNNING,
            automatedDiagnosticsPhaseStatus(
                listOf(
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.PREFLIGHT,
                        status = AutomatedDiagnosticStepStatus.PASS
                    ),
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.BLE_RUNTIME,
                        status = AutomatedDiagnosticStepStatus.RUNNING
                    ),
                    AutomatedDiagnosticStepResult(
                        stepId = AutomatedDiagnosticStepId.AURORA_PEER_DISCOVERY,
                        status = AutomatedDiagnosticStepStatus.WAITING
                    )
                )
            )
        )
    }

    @Test
    fun phaseReportsContainOnlyTheirStepRangesAndPreservePhaseSpecificEvidence() {
        val state = stateWithCustomSteps(
            AutomatedDiagnosticStepId.PREFLIGHT to AutomatedDiagnosticStepResult(
                stepId = AutomatedDiagnosticStepId.PREFLIGHT,
                status = AutomatedDiagnosticStepStatus.PASS,
                summary = "Preflight complete"
            ),
            AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET to AutomatedDiagnosticStepResult(
                stepId = AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET,
                status = AutomatedDiagnosticStepStatus.PASS,
                summary = "Socket ready"
            ),
            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE to AutomatedDiagnosticStepResult(
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Global probe waiting",
                blockerOrFailure = "Step 18 blocker",
                evidenceValues = listOf(
                    AutomatedDiagnosticEvidenceValue("GLOBAL C2P observed", "true"),
                    AutomatedDiagnosticEvidenceValue("Queued", "1"),
                    AutomatedDiagnosticEvidenceValue("Transport", "ble")
                )
            ),
            AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE to AutomatedDiagnosticStepResult(
                stepId = AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE,
                status = AutomatedDiagnosticStepStatus.PASS,
                summary = "Reverse direction probe complete",
                evidenceValues = listOf(
                    AutomatedDiagnosticEvidenceValue("Global direction", "participant->coordinator"),
                    AutomatedDiagnosticEvidenceValue("Private direction", "participant->coordinator")
                )
            ),
            AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION to AutomatedDiagnosticStepResult(
                stepId = AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION,
                status = AutomatedDiagnosticStepStatus.PASS,
                summary = "Cleanup verified",
                evidenceValues = listOf(
                    AutomatedDiagnosticEvidenceValue("Exact-id cleanup", "pass")
                )
            )
        ).copy(phaseTwoSummary = "Phase 2 footer")

        val phaseSections = automatedDiagnosticsPhaseSections(state)
        val phaseOneReport = phaseSections.first { it.phase == AutomatedDiagnosticsPhase.PHASE_1 }.reportText
        val phaseTwoReport = phaseSections.first { it.phase == AutomatedDiagnosticsPhase.PHASE_2 }.reportText
        val phaseThreeReport = phaseSections.first { it.phase == AutomatedDiagnosticsPhase.PHASE_3 }.reportText

        assertTrue(phaseOneReport.contains("Automated Aurora Test - Phase 1 Report"))
        assertTrue(phaseOneReport.contains("01 Preflight"))
        assertFalse(phaseOneReport.contains("11 Wi-Fi Direct discovery and group"))
        assertFalse(phaseOneReport.contains("18 Global message probe"))

        assertTrue(phaseTwoReport.contains("Automated Aurora Test - Phase 2 Report"))
        assertTrue(phaseTwoReport.contains("11 Wi-Fi Direct discovery and group"))
        assertTrue(phaseTwoReport.contains("12 Wi-Fi Direct socket"))
        assertTrue(phaseTwoReport.contains("Phase 2 footer"))
        assertFalse(phaseTwoReport.contains("10 BLE stability"))
        assertFalse(phaseTwoReport.contains("18 Global message probe"))

        assertTrue(phaseThreeReport.contains("Automated Aurora Test - Phase 3 Report"))
        assertTrue(phaseThreeReport.contains("18 Global message probe"))
        assertTrue(phaseThreeReport.contains("19 Private encrypted message probe"))
        assertTrue(phaseThreeReport.contains("20 Reverse-direction messaging probe"))
        assertTrue(phaseThreeReport.contains("21 Final end-to-end validation"))
        assertTrue(phaseThreeReport.contains("Step 18 blocker"))
        assertTrue(phaseThreeReport.contains("GLOBAL C2P observed: true"))
        assertTrue(phaseThreeReport.contains("Queued: 1"))
        assertTrue(phaseThreeReport.contains("Transport: ble"))
        assertTrue(phaseThreeReport.contains("Exact-id cleanup: pass"))
        assertFalse(phaseThreeReport.contains("17 Hybrid bootstrap trigger"))
    }

    private fun stateWithCustomSteps(
        vararg overrides: Pair<AutomatedDiagnosticStepId, AutomatedDiagnosticStepResult>
    ): AutomatedDiagnosticsRunState {
        val overrideMap = overrides.toMap()
        val steps = AutomatedDiagnosticStepId.entries.map { stepId ->
            overrideMap[stepId] ?: AutomatedDiagnosticStepResult(stepId = stepId)
        }
        return AutomatedDiagnosticsRunState.initial().copy(steps = steps)
    }
}
