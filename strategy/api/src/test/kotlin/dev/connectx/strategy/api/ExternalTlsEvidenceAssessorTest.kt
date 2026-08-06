package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalTlsEvidenceAssessorTest {
    @Test
    fun exactRestrictedPatternIsHighConfidenceConfirmedHelp() {
        val result = ExternalTlsEvidenceAssessor.assess(
            summary(
                baselineSuccesses = 0,
                baselineFailures = 3,
                strategySuccesses = 3,
                strategyFailures = 0,
                recoverySuccesses = 0,
                recoveryFailures = 3,
                reason = "STRATEGY_RESTORED_RESTRICTED_BASELINE",
            ),
        )

        assertEquals(ExternalTlsEvidenceClass.STRATEGY_HELP_CONFIRMED, result.evidenceClass)
        assertEquals(ExternalTlsEvidenceConfidence.HIGH, result.confidence)
        assertEquals(
            ExternalTlsEvidenceRecommendation.ATTACH_FOR_MANUAL_REVIEW,
            result.recommendation,
        )
    }

    @Test
    fun mixedButQuorumRestrictedPatternIsMediumConfidence() {
        val result = ExternalTlsEvidenceAssessor.assess(
            summary(
                baselineSuccesses = 1,
                baselineFailures = 2,
                strategySuccesses = 2,
                strategyFailures = 1,
                recoverySuccesses = 1,
                recoveryFailures = 2,
                reason = "STRATEGY_RESTORED_RESTRICTED_BASELINE",
            ),
        )

        assertEquals(ExternalTlsEvidenceClass.STRATEGY_HELP_CONFIRMED, result.evidenceClass)
        assertEquals(ExternalTlsEvidenceConfidence.MEDIUM, result.confidence)
    }

    @Test
    fun availableBaselineDoesNotClaimStrategyHelp() {
        val result = ExternalTlsEvidenceAssessor.assess(
            summary(
                baselineSuccesses = 3,
                baselineFailures = 0,
                strategySuccesses = 3,
                strategyFailures = 0,
                recoverySuccesses = 3,
                recoveryFailures = 0,
                reason = "PASSED_WITHIN_LATENCY_BUDGET",
            ),
        )

        assertEquals(ExternalTlsEvidenceClass.AVAILABLE_WITHOUT_STRATEGY, result.evidenceClass)
        assertEquals(
            ExternalTlsEvidenceRecommendation.STRATEGY_NOT_REQUIRED,
            result.recommendation,
        )
    }

    @Test
    fun regressionKeepsStrategyDisabled() {
        val result = ExternalTlsEvidenceAssessor.assess(
            summary(
                baselineSuccesses = 3,
                baselineFailures = 0,
                strategySuccesses = 1,
                strategyFailures = 2,
                recoverySuccesses = 3,
                recoveryFailures = 0,
                reason = "STRATEGY_LATENCY_REGRESSION",
            ),
        )

        assertEquals(ExternalTlsEvidenceClass.STRATEGY_NOT_HELPFUL, result.evidenceClass)
        assertEquals(
            ExternalTlsEvidenceRecommendation.KEEP_STRATEGY_DISABLED,
            result.recommendation,
        )
    }

    @Test
    fun incompleteSamplesAreAlwaysInconclusive() {
        val result = ExternalTlsEvidenceAssessor.assess(
            summary(
                baselineSuccesses = 0,
                baselineFailures = 2,
                strategySuccesses = 3,
                strategyFailures = 0,
                recoverySuccesses = 0,
                recoveryFailures = 3,
                reason = "STRATEGY_RESTORED_RESTRICTED_BASELINE",
            ),
        )

        assertEquals(ExternalTlsEvidenceClass.INCONCLUSIVE, result.evidenceClass)
        assertEquals(ExternalTlsEvidenceConfidence.LOW, result.confidence)
        assertEquals(
            ExternalTlsEvidenceRecommendation.REPEAT_ON_SAME_NETWORK,
            result.recommendation,
        )
    }

    private fun summary(
        baselineSuccesses: Int,
        baselineFailures: Int,
        strategySuccesses: Int,
        strategyFailures: Int,
        recoverySuccesses: Int,
        recoveryFailures: Int,
        reason: String,
    ) = ExternalTlsEvidenceSampleSummary(
        baselineSuccesses = baselineSuccesses,
        baselineFailures = baselineFailures,
        strategySuccesses = strategySuccesses,
        strategyFailures = strategyFailures,
        recoverySuccesses = recoverySuccesses,
        recoveryFailures = recoveryFailures,
        decision = "TEST_DECISION",
        reason = reason,
    )
}
