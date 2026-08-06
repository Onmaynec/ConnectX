package dev.connectx.app.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTlsEvidenceReportTest {
    @Test
    fun reportContainsSchemaAssessmentAndOnlyRedactedTarget() {
        val report = buildRedactedEvidenceReport(availableData())

        assertTrue(report.contains("ConnectX v0.3.0-alpha.6"))
        assertTrue(report.contains("schema_version=2"))
        assertTrue(report.contains(Regex("""report_id=[0-9a-f]{24}""")))
        assertTrue(report.contains("target_preset=telegram"))
        assertTrue(report.contains("target=redacted-public-host:443"))
        assertTrue(report.contains("samples_per_phase=3"))
        assertTrue(
            report.contains(
                "baseline=successes=3,failures=0,median_latency_ms=120,record=HANDSHAKE",
            ),
        )
        assertTrue(report.contains("evidence_class=AVAILABLE_WITHOUT_STRATEGY"))
        assertTrue(report.contains("confidence=HIGH"))
        assertTrue(report.contains("recommendation=STRATEGY_NOT_REQUIRED"))
        assertTrue(report.contains("hostname=REDACTED"))
        assertTrue(report.contains("resolved_ipv4=REDACTED"))
        assertTrue(report.contains("network-specific-repeated-evidence-not-universal-bypass"))
        assertTrue(validateRedactedEvidenceReport(report).isEmpty())
    }

    @Test
    fun reportIdIsDeterministicAndChangesWithEvidence() {
        val first = buildRedactedEvidenceReport(availableData())
        val second = buildRedactedEvidenceReport(availableData())
        val changed = buildRedactedEvidenceReport(
            availableData().copy(strategyLatencyMillis = 96L),
        )

        assertEquals(reportId(first), reportId(second))
        assertNotEquals(reportId(first), reportId(changed))
    }

    @Test
    fun reportCannotReceiveOrLeakHostnameIpPayloadOrErrorText() {
        val sensitiveValues = listOf(
            "blocked.example.org",
            "93.184.216.34",
            "secret-cookie",
            "Bearer token",
            "Connection refused for blocked.example.org",
        )
        val report = buildRedactedEvidenceReport(
            ExternalTlsEvidenceReportData(
                presetId = "custom",
                targetPort = null,
                baselineLatencyMillis = null,
                strategyLatencyMillis = null,
                recoveryLatencyMillis = null,
                baselineRecordKind = null,
                strategyRecordKind = null,
                recoveryRecordKind = null,
                baselineSuccesses = 0,
                baselineFailures = 3,
                strategySuccesses = 0,
                strategyFailures = 3,
                recoverySuccesses = 0,
                recoveryFailures = 3,
                decision = "ROLLBACK_CONFIRMED",
                reason = "STRATEGY_DID_NOT_RESTORE_RESTRICTED_BASELINE",
                gateState = "COOLDOWN",
            ),
        )

        sensitiveValues.forEach { value ->
            assertFalse("Report leaked: $value", report.contains(value))
        }
        assertTrue(report.contains("evidence_class=STRATEGY_NOT_HELPFUL"))
        assertTrue(report.contains("recommendation=KEEP_STRATEGY_DISABLED"))
        assertTrue(validateRedactedEvidenceReport(report).isEmpty())
    }

    @Test
    fun validatorRejectsUnknownFieldsRawTargetsAndUrls() {
        val valid = buildRedactedEvidenceReport(availableData())
        val unsafe = valid +
            "\nsource_url=https://blocked.example.org/path" +
            "\nresolved_ipv4=93.184.216.34"

        val violations = validateRedactedEvidenceReport(unsafe)

        assertTrue(violations.any { it.startsWith("unknown-key:") })
        assertTrue(violations.contains("duplicate-key:resolved_ipv4"))
        assertTrue(violations.contains("ipv4-not-redacted"))
        assertTrue(violations.contains("raw-ipv4"))
        assertTrue(violations.any { it.startsWith("forbidden-fragment:") })
    }

    private fun availableData() = ExternalTlsEvidenceReportData(
        presetId = "telegram",
        targetPort = 443,
        baselineLatencyMillis = 120L,
        strategyLatencyMillis = 95L,
        recoveryLatencyMillis = 118L,
        baselineRecordKind = "HANDSHAKE",
        strategyRecordKind = "ALERT",
        recoveryRecordKind = "HANDSHAKE",
        baselineSuccesses = 3,
        baselineFailures = 0,
        strategySuccesses = 3,
        strategyFailures = 0,
        recoverySuccesses = 3,
        recoveryFailures = 0,
        decision = "KEEP_FOR_LAB_SESSION",
        reason = "PASSED_WITHIN_LATENCY_BUDGET",
        gateState = "LAB_APPROVED",
    )

    private fun reportId(report: String): String =
        report.lineSequence()
            .single { it.startsWith("report_id=") }
            .substringAfter('=')
}
