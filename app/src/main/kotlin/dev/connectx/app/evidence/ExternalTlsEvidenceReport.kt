package dev.connectx.app.evidence

internal data class ExternalTlsEvidenceReportData(
    val presetId: String,
    val targetPort: Int?,
    val baselineLatencyMillis: Long?,
    val strategyLatencyMillis: Long?,
    val recoveryLatencyMillis: Long?,
    val baselineRecordKind: String?,
    val strategyRecordKind: String?,
    val recoveryRecordKind: String?,
    val baselineSuccesses: Int,
    val baselineFailures: Int,
    val strategySuccesses: Int,
    val strategyFailures: Int,
    val recoverySuccesses: Int,
    val recoveryFailures: Int,
    val decision: String?,
    val reason: String?,
    val gateState: String?,
)

internal fun buildRedactedEvidenceReport(data: ExternalTlsEvidenceReportData): String = buildString {
    appendLine("ConnectX v0.3.0-alpha.5 — repeated TLS strategy evidence")
    appendLine("target_preset=${data.presetId}")
    appendLine("target=redacted-public-host:${data.targetPort ?: 443}")
    appendLine("route=TEST-NET-only")
    appendLine("transport=TCP")
    appendLine("tls_payload=ClientHello-only")
    appendLine("samples_per_phase=3")
    appendLine("baseline=" + phaseReport(data.baselineLatencyMillis, data.baselineRecordKind, data.baselineSuccesses, data.baselineFailures))
    appendLine("strategy=" + phaseReport(data.strategyLatencyMillis, data.strategyRecordKind, data.strategySuccesses, data.strategyFailures))
    appendLine("recovery=" + phaseReport(data.recoveryLatencyMillis, data.recoveryRecordKind, data.recoverySuccesses, data.recoveryFailures))
    appendLine("decision=${data.decision ?: "UNKNOWN"}")
    appendLine("reason=${data.reason ?: "UNKNOWN"}")
    appendLine("gate=${data.gateState ?: "UNKNOWN"}")
    appendLine("hostname=REDACTED")
    appendLine("resolved_ipv4=REDACTED")
    append("claim=network-specific-repeated-evidence-not-universal-bypass")
}

private fun phaseReport(
    latencyMillis: Long?,
    recordKind: String?,
    successes: Int,
    failures: Int,
): String = listOfNotNull(
    "successes=$successes",
    "failures=$failures",
    latencyMillis?.let { "median_latency_ms=$it" },
    recordKind?.let { "record=$it" },
).joinToString(",")
