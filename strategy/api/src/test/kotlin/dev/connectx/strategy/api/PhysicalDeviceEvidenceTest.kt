package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalDeviceEvidenceTest {
    @Test
    fun abiClassificationExportsOnlyBroadFamily() {
        assertEquals(
            ExternalTlsEvidenceAbiFamily.ARM64,
            ExternalTlsEvidenceEnvironmentPolicy.classifyAbi("arm64-v8a"),
        )
        assertEquals(
            ExternalTlsEvidenceAbiFamily.X86_64,
            ExternalTlsEvidenceEnvironmentPolicy.classifyAbi("x86_64"),
        )
        assertEquals(
            ExternalTlsEvidenceAbiFamily.OTHER,
            ExternalTlsEvidenceEnvironmentPolicy.classifyAbi("armeabi-v7a"),
        )
        assertEquals(
            ExternalTlsEvidenceAbiFamily.UNKNOWN,
            ExternalTlsEvidenceEnvironmentPolicy.classifyAbi(null),
        )
    }

    @Test
    fun fdDeltaWithinBudgetIsAccepted() {
        val result = ExternalTlsEvidenceEnvironmentPolicy.assessFd(
            ExternalTlsEvidenceFdSample(before = 40, after = 43, allowedDelta = 4),
        )
        assertEquals(ExternalTlsEvidenceFdStatus.WITHIN_BUDGET, result.status)
        assertEquals(3, result.delta)
    }

    @Test
    fun fdDeltaAboveBudgetIsExplicitlyExceeded() {
        val result = ExternalTlsEvidenceEnvironmentPolicy.assessFd(
            ExternalTlsEvidenceFdSample(before = 40, after = 45, allowedDelta = 4),
        )
        assertEquals(ExternalTlsEvidenceFdStatus.EXCEEDED, result.status)
        assertEquals(5, result.delta)
    }

    @Test
    fun missingOrInvalidFdSamplesAreUnknown() {
        listOf(
            ExternalTlsEvidenceFdSample(before = null, after = 20),
            ExternalTlsEvidenceFdSample(before = 20, after = null),
            ExternalTlsEvidenceFdSample(before = -1, after = 20),
            ExternalTlsEvidenceFdSample(before = 20, after = 20, allowedDelta = -1),
        ).forEach { sample ->
            val result = ExternalTlsEvidenceEnvironmentPolicy.assessFd(sample)
            assertEquals(ExternalTlsEvidenceFdStatus.UNKNOWN, result.status)
            assertNull(result.delta)
        }
    }
}
