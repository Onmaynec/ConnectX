package dev.connectx.app.evidence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTlsEvidenceReportTest {
    @Test
    fun reportContainsOnlyRedactedTargetAndTechnicalOutcome() {
        val report = buildRedactedEvidenceReport(
            ExternalTlsEvidenceReportData(
                targetPort = 443,
                baselineLatencyMillis = 120L,
                strategyLatencyMillis = 95L,
                recoveryLatencyMillis = 118L,
                baselineRecordKind = "HANDSHAKE",
                strategyRecordKind = "ALERT",
                recoveryRecordKind = "HANDSHAKE",
                decision = "KEEP_FOR_LAB_SESSION",
                reason = "PASSED_WITHIN_LATENCY_BUDGET",
                gateState = "LAB_APPROVED",
            ),
        )

        assertTrue(report.contains("target=redacted-public-host:443"))
        assertTrue(report.contains("baseline=latency_ms=120,record=HANDSHAKE"))
        assertTrue(report.contains("strategy=latency_ms=95,record=ALERT"))
        assertTrue(report.contains("decision=KEEP_FOR_LAB_SESSION"))
        assertTrue(report.contains("hostname=REDACTED"))
        assertTrue(report.contains("resolved_ipv4=REDACTED"))
        assertTrue(report.contains("network-specific-lab-evidence-not-universal-bypass"))
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
                targetPort = null,
                baselineLatencyMillis = null,
                strategyLatencyMillis = null,
                recoveryLatencyMillis = null,
                baselineRecordKind = null,
                strategyRecordKind = null,
                recoveryRecordKind = null,
                decision = "REJECT",
                reason = "BASELINE_UNHEALTHY",
                gateState = "COOLDOWN",
            ),
        )

        sensitiveValues.forEach { value ->
            assertFalse("Report leaked: $value", report.contains(value))
        }
        assertTrue(report.contains("baseline=not_available"))
        assertTrue(report.contains("target=redacted-public-host:443"))
    }
}
