package dev.connectx.app.evidence

internal data class ExternalTlsEvidenceReportData(
    val targetPort: Int?,
    val baselineLatencyMillis: Long?,
    val strategyLatencyMillis: Long?,
    val recoveryLatencyMillis: Long?,
    val baselineRecordKind: String?,
    val strategyRecordKind: String?,
    val recoveryRecordKind: String?,
    val decision: String?,
    val reason: String?,
    val gateState: String?,
)

internal fun buildRedactedEvidenceReport(
    data: ExternalTlsEvidenceReportData,
): String = buildString {
    appendLine("ConnectX v0.3.0-alpha.3 — TLS Evidence Lab")
    appendLine("target=redacted-public-host:${data.targetPort ?: 443}")
    appendLine("route=TEST-NET-only")
    appendLine("transport=TCP")
    appendLine("tls_payload=ClientHello-only")
    appendLine(
        "baseline=" + phaseReport(
            data.baselineLatencyMillis,
            data.baselineRecordKind,
        ),
    )
    appendLine(
        "strategy=" + phaseReport(
            data.strategyLatencyMillis,
            data.strategyRecordKind,
        ),
    )
    appendLine(
        "recovery=" + phaseReport(
            data.recoveryLatencyMillis,
            data.recoveryRecordKind,
        ),
    )
    appendLine("decision=${data.decision ?: "UNKNOWN"}")
    appendLine("reason=${data.reason ?: "UNKNOWN"}")
    appendLine("gate=${data.gateState ?: "UNKNOWN"}")
    appendLine("hostname=REDACTED")
    appendLine("resolved_ipv4=REDACTED")
    append("claim=network-specific-lab-evidence-not-universal-bypass")
}

private fun phaseReport(latencyMillis: Long?, recordKind: String?): String =
    listOfNotNull(
        latencyMillis?.let { "latency_ms=$it" },
        recordKind?.let { "record=$it" },
    ).ifEmpty { listOf("not_available") }
        .joinToString(",")
