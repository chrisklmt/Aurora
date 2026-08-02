package gr.hua.aurora.wifidirect.controller

internal const val automatedDiagnosticsWifiDirectDnsSdServiceType = "_aurora-diag._tcp"
internal const val automatedDiagnosticsWifiDirectDnsSdInstanceName = "aurora-diag"
internal const val automatedDiagnosticsWifiDirectDnsSdProtocolVersion = "1"
internal const val automatedDiagnosticsWifiDirectDnsSdTokenTxtKey = "token"
internal const val automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey = "protocol"

internal fun automatedDiagnosticsWifiDirectDnsSdTxtRecord(
    correlationToken: String
): Map<String, String> {
    return mapOf(
        automatedDiagnosticsWifiDirectDnsSdTokenTxtKey to correlationToken,
        automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey to
            automatedDiagnosticsWifiDirectDnsSdProtocolVersion
    )
}
