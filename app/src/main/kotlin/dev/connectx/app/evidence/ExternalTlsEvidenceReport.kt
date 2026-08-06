package dev.connectx.app.evidence

import dev.connectx.strategy.api.DEFAULT_ALLOWED_FD_DELTA
import dev.connectx.strategy.api.ExternalTlsEvidenceAbiFamily
import dev.connectx.strategy.api.ExternalTlsEvidenceAssessor
import dev.connectx.strategy.api.ExternalTlsEvidenceDeviceClass
import dev.connectx.strategy.api.ExternalTlsEvidenceEnvironment
import dev.connectx.strategy.api.ExternalTlsEvidenceEnvironmentPolicy
import dev.connectx.strategy.api.ExternalTlsEvidenceFdSample
import dev.connectx.strategy.api.ExternalTlsEvidenceFdStatus
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
    val environment: ExternalTlsEvidenceEnvironment = ExternalTlsEvidenceEnvironment(
        deviceClass = ExternalTlsEvidenceDeviceClass.UNKNOWN,
        androidApi = null,
        abiFamily = ExternalTlsEvidenceAbiFamily.UNKNOWN,
    ),
    val fdBefore: Int? = null,
    val fdAfter: Int? = null,
    val fdAllowedDelta: Int = DEFAULT_ALLOWED_FD_DELTA,
)

internal fun buildRedactedEvidenceReport(data: ExternalTlsEvidenceReportData): String {
    val assessment = ExternalTlsEvidenceAssessor.assess(data.toSampleSummary())
    val fd = ExternalTlsEvidenceEnvironmentPolicy.assessFd(
        ExternalTlsEvidenceFdSample(
            before = data.fdBefore,
            after = data.fdAfter,
            allowedDelta = data.fdAllowedDelta,
        ),
    )
    val reportId = buildReportId(
        data = data,
        evidenceClass = assessment.evidenceClass.name,
        confidence = assessment.confidence.name,
        fdStatus = fd.status.name,
        fdDelta = fd.delta,
    )
    val report = buildString {
        appendLine(REPORT_HEADER)
        appendLine("schema_version=3")
        appendLine("report_id=$reportId")
        appendLine("target_preset=${data.presetId}")
        appendLine("target=redacted-public-host:${data.targetPort ?: 443}")
        appendLine("route=TEST-NET-only")
        appendLine("transport=TCP")
        appendLine("tls_payload=ClientHello-only")
        appendLine("samples_per_phase=3")
        appendLine("device_class=${data.environment.deviceClass.name}")
        appendLine("android_api=${data.environment.androidApi ?: "UNKNOWN"}")
        appendLine("abi_family=${data.environment.abiFamily.name}")
        appendLine("fd_before=${data.fdBefore ?: "UNKNOWN"}")
        appendLine("fd_after=${data.fdAfter ?: "UNKNOWN"}")
        appendLine("fd_delta=${fd.delta ?: "UNKNOWN"}")
        appendLine("fd_allowed_delta=${fd.allowedDelta}")
        appendLine("fd_status=${fd.status.name}")
        appendLine(
            "baseline=" + phaseReport(
                data.baselineLatencyMillis,
                data.baselineRecordKind,
                data.baselineSuccesses,
                data.baselineFailures,
            ),
        )
        appendLine(
            "strategy=" + phaseReport(
                data.strategyLatencyMillis,
                data.strategyRecordKind,
                data.strategySuccesses,
                data.strategyFailures,
            ),
        )
        appendLine(
            "recovery=" + phaseReport(
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

    if (entries.keys != ALLOWED_KEYS) violations += "schema-keys-mismatch"
    if (entries["schema_version"] != "3") violations += "invalid-schema-version"
    if (entries["hostname"] != "REDACTED") violations += "hostname-not-redacted"
    if (entries["resolved_ipv4"] != "REDACTED") violations += "ipv4-not-redacted"
    if (!entries["target"].orEmpty().matches(Regex("""redacted-public-host:\d{1,5}"""))) {
        violations += "invalid-redacted-target"
    }
    if (!entries["report_id"].orEmpty().matches(Regex("""[0-9a-f]{24}"""))) {
        violations += "invalid-report-id"
    }
    if (entries["device_class"] !in ExternalTlsEvidenceDeviceClass.entries.map { it.name }) {
        violations += "invalid-device-class"
    }
    if (entries["abi_family"] !in ExternalTlsEvidenceAbiFamily.entries.map { it.name }) {
        violations += "invalid-abi-family"
    }
    val api = entries["android_api"]
    if (api != "UNKNOWN" && api?.toIntOrNull()?.let { it in 29..99 } != true) {
        violations += "invalid-android-api"
    }

    val before = parseOptionalNonNegative(entries["fd_before"], "fd-before", violations)
    val after = parseOptionalNonNegative(entries["fd_after"], "fd-after", violations)
    val allowed = entries["fd_allowed_delta"]?.toIntOrNull()
    if (allowed == null || allowed !in 0..32) violations += "invalid-fd-budget"
    val reportedDelta = entries["fd_delta"]
    val parsedDelta = if (reportedDelta == "UNKNOWN") null else reportedDelta?.toIntOrNull()
    if (reportedDelta != "UNKNOWN" && parsedDelta == null) violations += "invalid-fd-delta"
    val expectedFd = ExternalTlsEvidenceEnvironmentPolicy.assessFd(
        ExternalTlsEvidenceFdSample(before, after, allowed ?: 0),
    )
    if (entries["fd_status"] !in ExternalTlsEvidenceFdStatus.entries.map { it.name }) {
        violations += "invalid-fd-status"
    } else if (entries["fd_status"] != expectedFd.status.name) {
        violations += "fd-status-mismatch"
    }
    if (parsedDelta != expectedFd.delta) violations += "fd-delta-mismatch"

    if (IPV4_REGEX.containsMatchIn(report)) violations += "raw-ipv4"
    if (HOSTNAME_REGEX.containsMatchIn(report)) violations += "raw-hostname"
    val lowercase = report.lowercase()
    FORBIDDEN_FRAGMENTS.forEach { fragment ->
        if (fragment in lowercase) violations += "forbidden-fragment:$fragment"
    }
    return violations.distinct()
}

private fun parseOptionalNonNegative(
    value: String?,
    field: String,
    violations: MutableList<String>,
): Int? {
    if (value == "UNKNOWN") return null
    val parsed = value?.toIntOrNull()
    if (parsed == null || parsed < 0) violations += "invalid-$field"
    return parsed?.takeIf { it >= 0 }
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
    fdStatus: String,
    fdDelta: Int?,
): String {
    val canonical = listOf(
        "schema=3",
        "version=0.3.0-alpha.7",
        "preset=${data.presetId}",
        "port=${data.targetPort ?: 443}",
        "device_class=${data.environment.deviceClass.name}",
        "android_api=${data.environment.androidApi ?: "UNKNOWN"}",
        "abi_family=${data.environment.abiFamily.name}",
        "fd_before=${data.fdBefore ?: "UNKNOWN"}",
        "fd_after=${data.fdAfter ?: "UNKNOWN"}",
        "fd_delta=${fdDelta ?: "UNKNOWN"}",
        "fd_status=$fdStatus",
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
    "ConnectX v0.3.0-alpha.7 — TLS strategy evidence report"
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
    "device_class",
    "android_api",
    "abi_family",
    "fd_before",
    "fd_after",
    "fd_delta",
    "fd_allowed_delta",
    "fd_status",
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
private val HOSTNAME_REGEX = Regex(
    """(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\b""",
)
private val FORBIDDEN_FRAGMENTS = listOf(
    "http://",
    "https://",
    "authorization",
    "bearer ",
    "cookie",
    "token=",
    "serial=",
    "model=",
    "manufacturer=",
    "fingerprint=",
    "ssid=",
    "@",
)
