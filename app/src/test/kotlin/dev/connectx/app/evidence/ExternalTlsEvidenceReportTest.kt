package dev.connectx.app.evidence

import dev.connectx.strategy.api.ExternalTlsEvidenceAbiFamily
import dev.connectx.strategy.api.ExternalTlsEvidenceDeviceClass
import dev.connectx.strategy.api.ExternalTlsEvidenceEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTlsEvidenceReportTest {
    @Test
    fun reportSchemaV3ContainsOnlyBroadEnvironmentAndBoundedFdEvidence() {
        val report = buildRedactedEvidenceReport(sampleData())

        assertTrue(report.contains("ConnectX v0.3.0-alpha.7"))
        assertTrue(report.contains("schema_version=3"))
        assertTrue(report.contains("device_class=PHYSICAL"))
        assertTrue(report.contains("android_api=35"))
        assertTrue(report.contains("abi_family=ARM64"))
        assertTrue(report.contains("fd_before=40"))
        assertTrue(report.contains("fd_after=42"))
        assertTrue(report.contains("fd_delta=2"))
        assertTrue(report.contains("fd_status=WITHIN_BUDGET"))
        assertTrue(report.contains("hostname=REDACTED"))
        assertTrue(report.contains("resolved_ipv4=REDACTED"))
        assertTrue(validateRedactedEvidenceReport(report).isEmpty())
    }

    @Test
    fun identicalAggregatesProduceSameIdAndFdChangeChangesIt() {
        val first = reportId(buildRedactedEvidenceReport(sampleData()))
        val second = reportId(buildRedactedEvidenceReport(sampleData()))
        val changed = reportId(
            buildRedactedEvidenceReport(sampleData(fdAfter = 45)),
        )
        assertEquals(first, second)
        assertNotEquals(first, changed)
    }

    @Test
    fun unknownFdSampleRemainsValidButExplicitlyUnknown() {
        val report = buildRedactedEvidenceReport(sampleData(fdBefore = null, fdAfter = null))
        assertTrue(report.contains("fd_before=UNKNOWN"))
        assertTrue(report.contains("fd_after=UNKNOWN"))
        assertTrue(report.contains("fd_delta=UNKNOWN"))
        assertTrue(report.contains("fd_status=UNKNOWN"))
        assertTrue(validateRedactedEvidenceReport(report).isEmpty())
    }

    @Test
    fun validatorRejectsUnknownFieldsRawTargetsAndDeviceIdentifiers() {
        val safe = buildRedactedEvidenceReport(sampleData())
        val mutations = listOf(
            safe + "\nextra_field=value",
            safe.replace("hostname=REDACTED", "hostname=blocked.example.org"),
            safe.replace("resolved_ipv4=REDACTED", "resolved_ipv4=93.184.216.34"),
            safe.replace("claim=", "model=Pixel\nclaim="),
            safe.replace("claim=", "serial=ABC123\nclaim="),
            safe.replace("claim=", "https://blocked.example.org\nclaim="),
        )
        mutations.forEach { report ->
            assertFalse(validateRedactedEvidenceReport(report).isEmpty())
        }
    }

    private fun sampleData(
        fdBefore: Int? = 40,
        fdAfter: Int? = 42,
    ) = ExternalTlsEvidenceReportData(
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
        environment = ExternalTlsEvidenceEnvironment(
            deviceClass = ExternalTlsEvidenceDeviceClass.PHYSICAL,
            androidApi = 35,
            abiFamily = ExternalTlsEvidenceAbiFamily.ARM64,
        ),
        fdBefore = fdBefore,
        fdAfter = fdAfter,
    )

    private fun reportId(report: String): String = report.lineSequence()
        .first { it.startsWith("report_id=") }
        .substringAfter('=')
}
