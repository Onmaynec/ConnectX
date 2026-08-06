package dev.connectx.app.evidence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTlsEvidenceReportTest {
    @Test
    fun reportContainsOnlyRedactedTargetAndRepeatedTechnicalOutcome() {
        val report = buildRedactedEvidenceReport(
            ExternalTlsEvidenceReportData(
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
            ),
        )

        assertTrue(report.contains("ConnectX v0.3.0-alpha.4"))
        assertTrue(report.contains("target_preset=telegram"))
        assertTrue(report.contains("target=redacted-public-host:443"))
        assertTrue(report.contains("samples_per_phase=3"))
        assertTrue(report.contains("baseline=successes=3,failures=0,median_latency_ms=120,record=HANDSHAKE"))
        assertTrue(report.contains("strategy=successes=3,failures=0,median_latency_ms=95,record=ALERT"))
        assertTrue(report.contains("decision=KEEP_FOR_LAB_SESSION"))
        assertTrue(report.contains("hostname=REDACTED"))
        assertTrue(report.contains("resolved_ipv4=REDACTED"))
        assertTrue(report.contains("network-specific-repeated-evidence-not-universal-bypass"))
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
        assertTrue(report.contains("baseline=successes=0,failures=3"))
        assertTrue(report.contains("target=redacted-public-host:443"))
    }
}
