package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTlsEvidencePresetTest {
    @Test
    fun presetIdsAndHostnamesAreSafeAndUnique() {
        assertEquals(ExternalTlsEvidencePreset.entries.size, ExternalTlsEvidencePreset.entries.map { it.id }.toSet().size)
        ExternalTlsEvidencePreset.entries.filter { it != ExternalTlsEvidencePreset.CUSTOM }.forEach { preset ->
            val result = ExternalTlsEvidenceTarget.validateHostname(requireNotNull(preset.hostname))
            assertTrue("${preset.name}: $result", result is HostnameValidationResult.Valid)
        }
    }

    @Test
    fun unknownPresetFallsBackToCustom() {
        assertEquals(ExternalTlsEvidencePreset.CUSTOM, ExternalTlsEvidencePreset.fromId("unknown"))
    }
}
