package gr.hua.aurora.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatusReader
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRequiredAction
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRequiredActionKind
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPreparationCommand
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPreparationItemStatus
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPreparationState
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPhaseSection
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsOverallStatus
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRunner
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticEvidenceValue
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticStepResult
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticStepStatus
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRunState
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsPhaseSections
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsPreparationCommand
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsCompactSummaryText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsCurrentRequiredActionStepOrNull
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsShouldAutoExpand
import gr.hua.aurora.diagnostics.automated.formatAutomatedDiagnosticsDuration
import gr.hua.aurora.ui.components.AuroraTopBar
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.wifidirect.controller.WifiDirectPermissionStatusReader

@Composable
internal fun AutomatedDiagnosticsScreen(
    runner: AutomatedDiagnosticsRunner,
    currentUsername: String,
    onRefreshWifiDirectStatus: () -> Unit,
    onRefreshBluetoothStatus: () -> Unit,
    onBack: () -> Unit
) {
    val runState by runner.state.collectAsStateWithLifecycle()
    var preparationRefreshNonce by remember {
        mutableStateOf(0)
    }
    DisposableEffect(runner) {
        runner.setAutomaticParticipationEnabled(true)
        onDispose {
            runner.setAutomaticParticipationEnabled(false)
        }
    }
    AutomatedDiagnosticsScreen(
        state = runState,
        preparationState = remember(runState, preparationRefreshNonce) {
            runner.currentPreparationState()
        },
        automaticPreparationPending = remember(runState, preparationRefreshNonce) {
            runner.automaticPreparationPending()
        },
        currentUsername = currentUsername,
        onStart = runner::start,
        onStop = runner::stop,
        onRetryFailedStep = runner::retryFailedStep,
        onResetReport = runner::resetReport,
        onRefreshWifiDirectStatus = onRefreshWifiDirectStatus,
        onRefreshBluetoothStatus = onRefreshBluetoothStatus,
        onPreparationStateChanged = {
            preparationRefreshNonce += 1
        },
        onBack = onBack
    )
}

@Composable
internal fun AutomatedDiagnosticsScreen(
    state: AutomatedDiagnosticsRunState,
    preparationState: AutomatedDiagnosticsPreparationState,
    automaticPreparationPending: Boolean,
    currentUsername: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetryFailedStep: () -> Unit,
    onResetReport: () -> Unit,
    onRefreshWifiDirectStatus: () -> Unit,
    onRefreshBluetoothStatus: () -> Unit,
    onPreparationStateChanged: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val manualStepExpansion = remember {
        mutableStateMapOf<String, Boolean>()
    }
    val manualPhaseExpansion = remember {
        mutableStateMapOf<String, Boolean>()
    }
    var pendingPreparationStart by remember {
        mutableStateOf(false)
    }
    var bluetoothPermissionRequestAttempted by remember {
        mutableStateOf(false)
    }
    var wifiDirectPermissionRequestAttempted by remember {
        mutableStateOf(false)
    }
    val requiredActionStep = automatedDiagnosticsCurrentRequiredActionStepOrNull(state)
    val activity = context.findActivity()
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        bluetoothPermissionRequestAttempted = true
        onRefreshBluetoothStatus()
        onPreparationStateChanged()
        if (!pendingPreparationStart && state.overallStatus != AutomatedDiagnosticsOverallStatus.IDLE) {
            onRetryFailedStep()
        }
    }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        wifiDirectPermissionRequestAttempted = true
        onRefreshWifiDirectStatus()
        onPreparationStateChanged()
        if (!pendingPreparationStart && state.overallStatus != AutomatedDiagnosticsOverallStatus.IDLE) {
            onRetryFailedStep()
        }
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshBluetoothStatus()
        onRefreshWifiDirectStatus()
        onPreparationStateChanged()
        if (!pendingPreparationStart && state.overallStatus != AutomatedDiagnosticsOverallStatus.IDLE) {
            onRetryFailedStep()
        }
    }
    val preparationCommand = remember(
        pendingPreparationStart,
        automaticPreparationPending,
        state.overallStatus,
        preparationState,
        bluetoothPermissionRequestAttempted,
        wifiDirectPermissionRequestAttempted
    ) {
        val manualCommand = automatedDiagnosticsPreparationCommand(
            isPreparationPending = pendingPreparationStart,
            currentOverallStatus = state.overallStatus,
            preparationState = preparationState,
            bluetoothPermissionRequestAttempted = bluetoothPermissionRequestAttempted,
            wifiDirectPermissionRequestAttempted = wifiDirectPermissionRequestAttempted
        )
        if (manualCommand != AutomatedDiagnosticsPreparationCommand.NONE) {
            manualCommand
        } else {
            automatedDiagnosticsPreparationCommand(
                isPreparationPending = automaticPreparationPending,
                currentOverallStatus = state.overallStatus,
                preparationState = preparationState,
                bluetoothPermissionRequestAttempted = bluetoothPermissionRequestAttempted,
                wifiDirectPermissionRequestAttempted = wifiDirectPermissionRequestAttempted,
                startWhenReady = false
            )
        }
    }
    val visiblePreparationState =
        pendingPreparationStart ||
            automaticPreparationPending ||
            state.overallStatus == AutomatedDiagnosticsOverallStatus.IDLE
    val activeAction = requiredActionStep?.requiredAction
        ?: if (visiblePreparationState) preparationState.requiredAction else null
    LaunchedEffect(
        preparationCommand,
        pendingPreparationStart,
        automaticPreparationPending,
        visiblePreparationState
    ) {
        when (preparationCommand) {
            AutomatedDiagnosticsPreparationCommand.START_RUN -> {
                pendingPreparationStart = false
                onStart()
            }
            AutomatedDiagnosticsPreparationCommand.REQUEST_BLUETOOTH_PERMISSIONS -> {
                val permissionStatus = BluetoothPermissionStatusReader.read(context)
                if (permissionStatus.missingPermissions.isNotEmpty()) {
                    bluetoothPermissionLauncher.launch(
                        permissionStatus.missingPermissions.toTypedArray()
                    )
                }
            }
            AutomatedDiagnosticsPreparationCommand.REQUEST_WIFI_DIRECT_PERMISSIONS -> {
                val permissionStatus = WifiDirectPermissionStatusReader.read(context)
                if (permissionStatus.missingPermissions.isNotEmpty()) {
                    wifiDirectPermissionLauncher.launch(
                        permissionStatus.missingPermissions.toTypedArray()
                    )
                }
            }
            AutomatedDiagnosticsPreparationCommand.NONE -> Unit
        }
    }
    val actionHandler = remember(
        context,
        activity,
        activeAction,
        bluetoothPermissionRequestAttempted,
        wifiDirectPermissionRequestAttempted,
        onRefreshBluetoothStatus,
        onRefreshWifiDirectStatus,
        onPreparationStateChanged,
        onRetryFailedStep,
        bluetoothPermissionLauncher,
        wifiDirectPermissionLauncher,
        settingsLauncher
    ) {
        {
            val action = activeAction
            if (action != null) {
                when (action.kind) {
                    AutomatedDiagnosticsRequiredActionKind.REQUEST_BLUETOOTH_PERMISSIONS -> {
                        val permissionStatus = BluetoothPermissionStatusReader.read(context)
                        if (
                            shouldOpenAppSettingsForMissingPermissions(
                                activity = activity,
                                missingPermissions = permissionStatus.missingPermissions,
                                requestAttempted = bluetoothPermissionRequestAttempted
                            )
                        ) {
                            settingsLauncher.launch(context.applicationDetailsSettingsIntent())
                        } else if (permissionStatus.missingPermissions.isNotEmpty()) {
                            bluetoothPermissionLauncher.launch(
                                permissionStatus.missingPermissions.toTypedArray()
                            )
                        } else {
                            onRefreshBluetoothStatus()
                            onPreparationStateChanged()
                            if (
                                !pendingPreparationStart &&
                                state.overallStatus != AutomatedDiagnosticsOverallStatus.IDLE
                            ) {
                                onRetryFailedStep()
                            }
                        }
                    }
                    AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS -> {
                        val permissionStatus = WifiDirectPermissionStatusReader.read(context)
                        if (
                            shouldOpenAppSettingsForMissingPermissions(
                                activity = activity,
                                missingPermissions = permissionStatus.missingPermissions,
                                requestAttempted = wifiDirectPermissionRequestAttempted
                            )
                        ) {
                            settingsLauncher.launch(context.applicationDetailsSettingsIntent())
                        } else if (permissionStatus.missingPermissions.isNotEmpty()) {
                            wifiDirectPermissionLauncher.launch(
                                permissionStatus.missingPermissions.toTypedArray()
                            )
                        } else {
                            onRefreshWifiDirectStatus()
                            onPreparationStateChanged()
                            if (
                                !pendingPreparationStart &&
                                state.overallStatus != AutomatedDiagnosticsOverallStatus.IDLE
                            ) {
                                onRetryFailedStep()
                            }
                        }
                    }
                    AutomatedDiagnosticsRequiredActionKind.OPEN_BLUETOOTH_SETTINGS -> {
                        settingsLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                    AutomatedDiagnosticsRequiredActionKind.OPEN_LOCATION_SETTINGS -> {
                        settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    AutomatedDiagnosticsRequiredActionKind.OPEN_WIFI_SETTINGS -> {
                        settingsLauncher.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                    null -> Unit
                }
            }
        }
    }
    val actionButtonLabel = remember(
        context,
        activity,
        activeAction,
        bluetoothPermissionRequestAttempted,
        wifiDirectPermissionRequestAttempted
    ) {
        requiredActionButtonLabel(
            context = context,
            activity = activity,
            action = activeAction,
            bluetoothPermissionRequestAttempted = bluetoothPermissionRequestAttempted,
            wifiDirectPermissionRequestAttempted = wifiDirectPermissionRequestAttempted
        )
    }
    val phaseSections = remember(state) {
        automatedDiagnosticsPhaseSections(state)
    }

    Scaffold(
        topBar = {
            AuroraTopBar(
                title = "Automated Aurora Test",
                subtitle = automatedDiagnosticsCompactSummaryText(state),
                username = currentUsername,
                rightAction = AuroraTopBarAction.BACK,
                onRightActionClick = onBack
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AutomatedDiagnosticsSummaryCard(state = state)
            }
            item {
                AutomatedDiagnosticsControlsRow(
                    onStart = {
                        pendingPreparationStart = true
                        onPreparationStateChanged()
                    },
                    onStop = {
                        pendingPreparationStart = false
                        onStop()
                    },
                    onRetryFailedStep = onRetryFailedStep,
                    onResetReport = {
                        pendingPreparationStart = false
                        onResetReport()
                    }
                )
            }
            if (visiblePreparationState) {
                item {
                    AutomatedDiagnosticsPreparationCard(
                        preparationState = preparationState,
                        buttonLabel = actionButtonLabel,
                        onAction = actionHandler
                    )
                }
            }
            requiredActionStep?.let { step ->
                item {
                    AutomatedDiagnosticsRequiredActionCard(
                        step = step,
                        buttonLabel = actionButtonLabel,
                        onAction = actionHandler
                    )
                }
            }
            item {
                SectionHeader(title = "Steps")
            }
            items(
                items = phaseSections,
                key = { it.phase.name }
            ) { phaseSection ->
                val expanded = manualPhaseExpansion[phaseSection.phase.name]
                    ?: automatedDiagnosticsShouldAutoExpand(phaseSection.aggregatedStatus)
                AutomatedDiagnosticsPhaseCard(
                    phaseSection = phaseSection,
                    expanded = expanded,
                    onToggleExpanded = {
                        manualPhaseExpansion[phaseSection.phase.name] = !expanded
                    },
                    stepExpanded = { step ->
                        manualStepExpansion[step.stepId.name]
                            ?: automatedDiagnosticsShouldAutoExpand(step)
                    },
                    onStepToggleExpanded = { step, expandedStep ->
                        manualStepExpansion[step.stepId.name] = !expandedStep
                    }
                )
            }
            item {
                SectionHeader(title = "Reports")
            }
            items(
                items = phaseSections,
                key = { "${it.phase.name}-report" }
            ) { phaseSection ->
                AutomatedDiagnosticsReportCard(
                    title = phaseSection.phase.reportTitle,
                    onCopyReport = {
                        clipboardManager.setText(AnnotatedString(phaseSection.reportText))
                    }
                )
            }
            item {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        clipboardManager.setText(AnnotatedString(state.reportText))
                    }
                ) {
                    Text("Copy full report")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall
    )
}

@Composable
private fun AutomatedDiagnosticsPreparationCard(
    preparationState: AutomatedDiagnosticsPreparationState,
    buttonLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Preparing automated test",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = preparationState.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            preparationState.items.forEach { item ->
                val statusText = when (item.status) {
                    AutomatedDiagnosticsPreparationItemStatus.READY -> "READY"
                    AutomatedDiagnosticsPreparationItemStatus.WAITING -> "WAITING"
                }
                Text(
                    text = "${item.label}: $statusText",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            preparationState.requiredAction?.let {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAction
                ) {
                    Text(buttonLabel)
                }
            }
        }
    }
}

@Composable
private fun AutomatedDiagnosticsRequiredActionCard(
    step: AutomatedDiagnosticStepResult,
    buttonLabel: String,
    onAction: () -> Unit
) {
    val action = step.requiredAction ?: return
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = step.blockerOrFailure ?: step.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAction
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun AutomatedDiagnosticsPhaseCard(
    phaseSection: AutomatedDiagnosticsPhaseSection,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    stepExpanded: (AutomatedDiagnosticStepResult) -> Boolean,
    onStepToggleExpanded: (AutomatedDiagnosticStepResult, Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${phaseSection.phase.title} (${phaseSection.steps.first().visibleStepNumber.toString().padStart(2, '0')}-${phaseSection.steps.last().visibleStepNumber.toString().padStart(2, '0')})",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = phaseSection.aggregatedStatus.name,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    phaseSection.steps.forEach { step ->
                        val expandedStep = stepExpanded(step)
                        AutomatedDiagnosticsStepCard(
                            step = step,
                            expanded = expandedStep,
                            onToggleExpanded = {
                                onStepToggleExpanded(step, expandedStep)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomatedDiagnosticsSummaryCard(
    state: AutomatedDiagnosticsRunState
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Overall: ${state.overallStatus.name}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Current step: ${state.currentStepNumber ?: 0}/${state.totalSteps}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Elapsed: ${formatAutomatedDiagnosticsDuration(state.elapsedMillis)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Passed: ${state.passedCount}  Failed: ${state.failedCount}  Blocked: ${state.blockedCount}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Peer: ${state.selectedPeerId ?: "none"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Role: ${state.localPeerRole?.name ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AutomatedDiagnosticsReportCard(
    title: String,
    onCopyReport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                TextButton(onClick = onCopyReport) {
                    Text("Copy report")
                }
            }
        }
    }
}

@Composable
private fun AutomatedDiagnosticsControlsRow(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetryFailedStep: () -> Unit,
    onResetReport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onStart
            ) {
                Text("Run full automatic test")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onStop
                ) {
                    Text("Stop")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onRetryFailedStep
                ) {
                    Text("Retry failed step")
                }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onResetReport
            ) {
                Text("Reset report")
            }
        }
    }
}

@Composable
private fun AutomatedDiagnosticsStepCard(
    step: AutomatedDiagnosticStepResult,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = automatedDiagnosticsStepHeadline(step),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = step.status.name,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (expanded) {
                Text(
                    text = step.summary,
                    style = MaterialTheme.typography.bodySmall
                )
                step.waitingProgressText?.let { waiting ->
                    Text(
                        text = waiting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                step.stabilizationProgressText?.let { stabilization ->
                    Text(
                        text = stabilization,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (step.retryCount > 0) {
                    Text(
                        text = "Retries: ${step.retryCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                step.blockerOrFailure?.let { blocker ->
                    Text(
                        text = blocker,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                step.evidenceValues.forEach { evidence ->
                    AutomatedDiagnosticsEvidenceLine(evidence = evidence)
                }
                step.technicalDetails.forEach { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomatedDiagnosticsEvidenceLine(
    evidence: AutomatedDiagnosticEvidenceValue
) {
    Text(
        text = "${evidence.label}: ${evidence.value}",
        style = MaterialTheme.typography.bodySmall
    )
}

internal fun automatedDiagnosticsStepHeadline(
    step: AutomatedDiagnosticStepResult
): String {
    return "${step.visibleStepNumber.toString().padStart(2, '0')} ${step.title}"
}

private fun requiredActionButtonLabel(
    context: Context,
    activity: Activity?,
    action: AutomatedDiagnosticsRequiredAction?,
    bluetoothPermissionRequestAttempted: Boolean,
    wifiDirectPermissionRequestAttempted: Boolean
): String {
    return when (action?.kind) {
        AutomatedDiagnosticsRequiredActionKind.REQUEST_BLUETOOTH_PERMISSIONS -> {
            val permissionStatus = BluetoothPermissionStatusReader.read(context)
            if (
                shouldOpenAppSettingsForMissingPermissions(
                    activity = activity,
                    missingPermissions = permissionStatus.missingPermissions,
                    requestAttempted = bluetoothPermissionRequestAttempted
                )
            ) {
                "Open app settings"
            } else {
                action.buttonLabel
            }
        }
        AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS -> {
            val permissionStatus = WifiDirectPermissionStatusReader.read(context)
            if (
                shouldOpenAppSettingsForMissingPermissions(
                    activity = activity,
                    missingPermissions = permissionStatus.missingPermissions,
                    requestAttempted = wifiDirectPermissionRequestAttempted
                )
            ) {
                "Open app settings"
            } else {
                action.buttonLabel
            }
        }
        null -> ""
        else -> action.buttonLabel
    }
}

private fun shouldOpenAppSettingsForMissingPermissions(
    activity: Activity?,
    missingPermissions: Set<String>,
    requestAttempted: Boolean
): Boolean {
    if (!requestAttempted || activity == null || missingPermissions.isEmpty()) {
        return false
    }
    return missingPermissions.all { permission ->
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
}

private fun Context.findActivity(): Activity? {
    var currentContext: Context? = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}

private fun Context.applicationDetailsSettingsIntent(): Intent {
    return Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    )
}
