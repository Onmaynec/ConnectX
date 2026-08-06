package dev.connectx.app.evidence

import dev.connectx.strategy.api.ExternalTlsEvidenceAssessor
import dev.connectx.strategy.api.ExternalTlsEvidenceSampleSummary
import java.security.MessageDigest

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

internal fun buildRedactedEvidenceReport(data: ExternalTlsEvidenceReportData): String {
    val summary = data.toSampleSummary()
    val assessment = ExternalTlsEvidenceAssessor.assess(summary)
    val reportId = buildReportId(data, assessment.evidenceClass.name, assessment.confidence.name)
    val report = buildString {
        appendLine("ConnectX v0.3.0-alpha.6 — TLS strategy evidence report")
        appendLine("schema_version=2")
        appendLine("report_id=$reportId")
        appendLine("target_preset=${data.presetId}")
        appendLine("target=redacted-public-host:${data.targetPort ?: 443}")
        appendLine("route=TEST-NET-only")
        appendLine("transport=TCP")
        appendLine("tls_payload=ClientHello-only")
        appendLine("samples_per_phase=3")
        appendLine(
            "baseline=" +
                phaseReport(
                    data.baselineLatencyMillis,
                    data.baselineRecordKind,
                    data.baselineSuccesses,
                    data.baselineFailures,
                ),
        )
        appendLine(
            "strategy=" +
                phaseReport(
                    data.strategyLatencyMillis,
                    data.strategyRecordKind,
                    data.strategySuccesses,
                    data.strategyFailures,
                ),
        )
        appendLine(
            "recovery=" +
                phaseReport(
                    data.recoveryLatencyMillis,
                    data.recoveryRecordKind,
                    data.recoverySuccesses,
                    data.recoveryFailures,
                ),
        )
        appendLine("evidence_class=${assessment.evidenceClass.name}")
        appendLine("confidence=${assessment.confidence.name}")
        appendLine("recommendation=${assessment.recommendation.name}")
        appendLine("decision=${data.decision ?: "UNKNOWN"}")
        appendLine("reason=${data.reason ?: "UNKNOWN"}")
        appendLine("gate=${data.gateState ?: "UNKNOWN"}")
        appendLine("hostname=REDACTED")
        appendLine("resolved_ipv4=REDACTED")
        append("claim=network-specific-repeated-evidence-not-universal-bypass")
    }

    val violations = validateRedactedEvidenceReport(report)
    check(violations.isEmpty()) {
        "Generated evidence report violated its schema: ${violations.joinToString()}"
    }
    return report
}

internal fun validateRedactedEvidenceReport(report: String): List<String> {
    val violations = mutableListOf<String>()
    if (report.length > MAX_REPORT_CHARS) violations += "report-too-large"
    if ('\r' in report || '\u0000' in report) violations += "invalid-control-character"

    val lines = report.lines()
    if (lines.firstOrNull() != REPORT_HEADER) violations += "invalid-header"

    val entries = linkedMapOf<String, String>()
    lines.drop(1).forEach { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) {
            violations += "invalid-line"
            return@forEach
        }
        val key = line.substring(0, separator)
        val value = line.substring(separator + 1)
        if (key !in ALLOWED_KEYS) violations += "unknown-key:$key"
        if (entries.put(key, value) != null) violations += "duplicate-key:$key"
    }

    if (entries.keys != ALLOWED_KEYS) {
        violations += "schema-keys-mismatch"
    }
    if (entries["schema_version"] != "2") violations += "invalid-schema-version"
    if (entries["hostname"] != "REDACTED") violations += "hostname-not-redacted"
    if (entries["resolved_ipv4"] != "REDACTED") violations += "ipv4-not-redacted"
    if (!entries["target"].orEmpty().matches(Regex("""redacted-public-host:\d{1,5}"""))) {
        violations += "invalid-redacted-target"
    }
    if (!entries["report_id"].orEmpty().matches(Regex("""[0-9a-f]{24}"""))) {
        violations += "invalid-report-id"
    }

    if (IPV4_REGEX.containsMatchIn(report)) violations += "raw-ipv4"
    val lowercase = report.lowercase()
    FORBIDDEN_FRAGMENTS.forEach { fragment ->
        if (fragment in lowercase) violations += "forbidden-fragment:$fragment"
    }
    return violations.distinct()
}

private fun ExternalTlsEvidenceReportData.toSampleSummary() =
    ExternalTlsEvidenceSampleSummary(
        samplesPerPhase = 3,
        baselineSuccesses = baselineSuccesses,
        baselineFailures = baselineFailures,
        strategySuccesses = strategySuccesses,
        strategyFailures = strategyFailures,
        recoverySuccesses = recoverySuccesses,
        recoveryFailures = recoveryFailures,
        decision = decision,
        reason = reason,
    )

private fun buildReportId(
    data: ExternalTlsEvidenceReportData,
    evidenceClass: String,
    confidence: String,
): String {
    val canonical = listOf(
        "schema=2",
        "version=0.3.0-alpha.6",
        "preset=${data.presetId}",
        "port=${data.targetPort ?: 443}",
        "baseline_latency=${data.baselineLatencyMillis ?: -1}",
        "strategy_latency=${data.strategyLatencyMillis ?: -1}",
        "recovery_latency=${data.recoveryLatencyMillis ?: -1}",
        "baseline_record=${data.baselineRecordKind ?: "UNKNOWN"}",
        "strategy_record=${data.strategyRecordKind ?: "UNKNOWN"}",
        "recovery_record=${data.recoveryRecordKind ?: "UNKNOWN"}",
        "baseline=${data.baselineSuccesses}/${data.baselineFailures}",
        "strategy=${data.strategySuccesses}/${data.strategyFailures}",
        "recovery=${data.recoverySuccesses}/${data.recoveryFailures}",
        "decision=${data.decision ?: "UNKNOWN"}",
        "reason=${data.reason ?: "UNKNOWN"}",
        "gate=${data.gateState ?: "UNKNOWN"}",
        "class=$evidenceClass",
        "confidence=$confidence",
    ).joinToString("\n")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .take(REPORT_ID_BYTES)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
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

private const val REPORT_HEADER =
    "ConnectX v0.3.0-alpha.6 — TLS strategy evidence report"
private const val MAX_REPORT_CHARS = 4_096
private const val REPORT_ID_BYTES = 12

private val ALLOWED_KEYS = linkedSetOf(
    "schema_version",
    "report_id",
    "target_preset",
    "target",
    "route",
    "transport",
    "tls_payload",
    "samples_per_phase",
    "baseline",
    "strategy",
    "recovery",
    "evidence_class",
    "confidence",
    "recommendation",
    "decision",
    "reason",
    "gate",
    "hostname",
    "resolved_ipv4",
    "claim",
)

private val IPV4_REGEX = Regex("""(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])""")
private val FORBIDDEN_FRAGMENTS = listOf(
    "http://",
    "https://",
    "authorization",
    "bearer ",
    "cookie",
    "token=",
    "@",
)
