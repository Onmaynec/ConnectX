package dev.connectx.strategy.api

enum class ExternalTlsEvidenceClass {
    STRATEGY_HELP_CONFIRMED,
    AVAILABLE_WITHOUT_STRATEGY,
    STRATEGY_NOT_HELPFUL,
    INCONCLUSIVE,
}

enum class ExternalTlsEvidenceConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

enum class ExternalTlsEvidenceRecommendation {
    ATTACH_FOR_MANUAL_REVIEW,
    STRATEGY_NOT_REQUIRED,
    KEEP_STRATEGY_DISABLED,
    REPEAT_ON_SAME_NETWORK,
}

data class ExternalTlsEvidenceSampleSummary(
    val samplesPerPhase: Int = 3,
    val baselineSuccesses: Int,
    val baselineFailures: Int,
    val strategySuccesses: Int,
    val strategyFailures: Int,
    val recoverySuccesses: Int,
    val recoveryFailures: Int,
    val decision: String?,
    val reason: String?,
)

data class ExternalTlsEvidenceAssessment(
    val evidenceClass: ExternalTlsEvidenceClass,
    val confidence: ExternalTlsEvidenceConfidence,
    val recommendation: ExternalTlsEvidenceRecommendation,
)

object ExternalTlsEvidenceAssessor {
    fun assess(summary: ExternalTlsEvidenceSampleSummary): ExternalTlsEvidenceAssessment {
        if (!summary.hasCompleteValidSamples()) {
            return ExternalTlsEvidenceAssessment(
                evidenceClass = ExternalTlsEvidenceClass.INCONCLUSIVE,
                confidence = ExternalTlsEvidenceConfidence.LOW,
                recommendation = ExternalTlsEvidenceRecommendation.REPEAT_ON_SAME_NETWORK,
            )
        }

        val quorum = (summary.samplesPerPhase / 2) + 1
        return when (summary.reason) {
            REASON_STRATEGY_RESTORED_RESTRICTED_BASELINE -> {
                val exact = summary.baselineFailures == summary.samplesPerPhase &&
                    summary.strategySuccesses == summary.samplesPerPhase &&
                    summary.recoveryFailures == summary.samplesPerPhase
                val consistent = summary.baselineFailures >= quorum &&
                    summary.strategySuccesses >= quorum &&
                    summary.recoveryFailures >= quorum

                if (consistent) {
                    ExternalTlsEvidenceAssessment(
                        evidenceClass = ExternalTlsEvidenceClass.STRATEGY_HELP_CONFIRMED,
                        confidence = if (exact) {
                            ExternalTlsEvidenceConfidence.HIGH
                        } else {
                            ExternalTlsEvidenceConfidence.MEDIUM
                        },
                        recommendation = ExternalTlsEvidenceRecommendation.ATTACH_FOR_MANUAL_REVIEW,
                    )
                } else {
                    inconclusive()
                }
            }

            REASON_PASSED_WITHIN_LATENCY_BUDGET -> {
                val available = summary.baselineSuccesses >= quorum &&
                    summary.strategySuccesses >= quorum &&
                    summary.recoverySuccesses >= quorum
                if (available) {
                    ExternalTlsEvidenceAssessment(
                        evidenceClass = ExternalTlsEvidenceClass.AVAILABLE_WITHOUT_STRATEGY,
                        confidence = if (
                            summary.baselineSuccesses == summary.samplesPerPhase &&
                            summary.strategySuccesses == summary.samplesPerPhase &&
                            summary.recoverySuccesses == summary.samplesPerPhase
                        ) {
                            ExternalTlsEvidenceConfidence.HIGH
                        } else {
                            ExternalTlsEvidenceConfidence.MEDIUM
                        },
                        recommendation = ExternalTlsEvidenceRecommendation.STRATEGY_NOT_REQUIRED,
                    )
                } else {
                    inconclusive()
                }
            }

            REASON_STRATEGY_DID_NOT_RESTORE_RESTRICTED_BASELINE,
            REASON_STRATEGY_LATENCY_REGRESSION,
            REASON_STRATEGY_FAILURE_BUDGET_EXCEEDED,
            -> ExternalTlsEvidenceAssessment(
                evidenceClass = ExternalTlsEvidenceClass.STRATEGY_NOT_HELPFUL,
                confidence = ExternalTlsEvidenceConfidence.HIGH,
                recommendation = ExternalTlsEvidenceRecommendation.KEEP_STRATEGY_DISABLED,
            )

            REASON_RESTRICTED_BASELINE_NOT_REPRODUCED -> ExternalTlsEvidenceAssessment(
                evidenceClass = ExternalTlsEvidenceClass.INCONCLUSIVE,
                confidence = ExternalTlsEvidenceConfidence.MEDIUM,
                recommendation = ExternalTlsEvidenceRecommendation.REPEAT_ON_SAME_NETWORK,
            )

            else -> inconclusive()
        }
    }

    private fun ExternalTlsEvidenceSampleSummary.hasCompleteValidSamples(): Boolean {
        if (samplesPerPhase <= 0) return false
        val values = listOf(
            baselineSuccesses,
            baselineFailures,
            strategySuccesses,
            strategyFailures,
            recoverySuccesses,
            recoveryFailures,
        )
        if (values.any { it < 0 || it > samplesPerPhase }) return false
        return baselineSuccesses + baselineFailures == samplesPerPhase &&
            strategySuccesses + strategyFailures == samplesPerPhase &&
            recoverySuccesses + recoveryFailures == samplesPerPhase
    }

    private fun inconclusive() = ExternalTlsEvidenceAssessment(
        evidenceClass = ExternalTlsEvidenceClass.INCONCLUSIVE,
        confidence = ExternalTlsEvidenceConfidence.LOW,
        recommendation = ExternalTlsEvidenceRecommendation.REPEAT_ON_SAME_NETWORK,
    )

    private const val REASON_STRATEGY_RESTORED_RESTRICTED_BASELINE =
        "STRATEGY_RESTORED_RESTRICTED_BASELINE"
    private const val REASON_PASSED_WITHIN_LATENCY_BUDGET =
        "PASSED_WITHIN_LATENCY_BUDGET"
    private const val REASON_STRATEGY_DID_NOT_RESTORE_RESTRICTED_BASELINE =
        "STRATEGY_DID_NOT_RESTORE_RESTRICTED_BASELINE"
    private const val REASON_RESTRICTED_BASELINE_NOT_REPRODUCED =
        "RESTRICTED_BASELINE_NOT_REPRODUCED"
    private const val REASON_STRATEGY_LATENCY_REGRESSION =
        "STRATEGY_LATENCY_REGRESSION"
    private const val REASON_STRATEGY_FAILURE_BUDGET_EXCEEDED =
        "STRATEGY_FAILURE_BUDGET_EXCEEDED"
}
