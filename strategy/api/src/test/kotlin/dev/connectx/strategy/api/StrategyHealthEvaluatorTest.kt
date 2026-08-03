package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyHealthEvaluatorTest {
    private val strategyId = StrategyId("tls-clienthello-split-v1")

    @Test
    fun healthyAbaEvaluationKeepsStrategyForLabSession() {
        val report = evaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(40L, 60L),
            strategySamples = successes(70L, 80L),
            recoverySamples = successes(45L, 55L),
        )

        assertEquals(
            StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION,
            report.decision,
        )
        assertEquals(
            StrategyEvaluationReason.PASSED_WITHIN_LATENCY_BUDGET,
            report.reason,
        )
        assertEquals(25L, report.latencyDeltaMillis)
        assertEquals(150L, report.allowedStrategyLatencyMillis)
        assertEquals(50L, report.baseline.medianLatencyMillis)
        assertEquals(75L, report.strategy.medianLatencyMillis)
        assertEquals(50L, report.recovery.medianLatencyMillis)
    }

    @Test
    fun strategyFailureWithHealthyRecoveryConfirmsRollback() {
        val report = evaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(30L),
            strategySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.TIMEOUT),
            ),
            recoverySamples = successes(35L),
        )

        assertEquals(StrategyEvaluationDecision.ROLLBACK_CONFIRMED, report.decision)
        assertEquals(
            StrategyEvaluationReason.STRATEGY_FAILURE_BUDGET_EXCEEDED,
            report.reason,
        )
        assertEquals(1, report.strategy.failureReasons[StrategySampleFailure.TIMEOUT])
    }

    @Test
    fun unhealthyBaselineIsNotBlamedOnStrategy() {
        val report = evaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.CONNECTION_FAILED),
            ),
            strategySamples = successes(10L),
            recoverySamples = successes(10L),
        )

        assertEquals(
            StrategyEvaluationDecision.REJECT_BASELINE_UNHEALTHY,
            report.decision,
        )
        assertEquals(
            StrategyEvaluationReason.BASELINE_FAILURE_BUDGET_EXCEEDED,
            report.reason,
        )
    }

    @Test
    fun baselineFailureKeepsPriorityWhenRecoveryAlsoFails() {
        val report = evaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.CONNECTION_FAILED),
            ),
            strategySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.TIMEOUT),
            ),
            recoverySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.PAYLOAD_MISMATCH),
            ),
        )

        assertEquals(
            StrategyEvaluationDecision.REJECT_BASELINE_UNHEALTHY,
            report.decision,
        )
        assertEquals(
            StrategyEvaluationReason.BASELINE_FAILURE_BUDGET_EXCEEDED,
            report.reason,
        )
    }

    @Test
    fun failedRecoveryMarksEnvironmentUnstable() {
        val report = evaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(20L),
            strategySamples = successes(20L),
            recoverySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.PAYLOAD_MISMATCH),
            ),
        )

        assertEquals(
            StrategyEvaluationDecision.REJECT_ENVIRONMENT_UNSTABLE,
            report.decision,
        )
        assertEquals(
            StrategyEvaluationReason.RECOVERY_FAILURE_BUDGET_EXCEEDED,
            report.reason,
        )
    }

    @Test
    fun recoveryFailureOutranksStrategyFailure() {
        val report = evaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(20L),
            strategySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.TIMEOUT),
            ),
            recoverySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.CONNECTION_FAILED),
            ),
        )

        assertEquals(
            StrategyEvaluationDecision.REJECT_ENVIRONMENT_UNSTABLE,
            report.decision,
        )
        assertEquals(
            StrategyEvaluationReason.RECOVERY_FAILURE_BUDGET_EXCEEDED,
            report.reason,
        )
    }

    @Test
    fun excessiveLatencyRegressionConfirmsRollback() {
        val report = evaluator(
            StrategyEvaluationPolicy(
                maxLatencyRegressionPercent = 10,
                maxAbsoluteLatencyRegressionMillis = 5L,
            ),
        ).evaluate(
            strategyId = strategyId,
            baselineSamples = successes(100L),
            strategySamples = successes(111L),
            recoverySamples = successes(100L),
        )

        assertEquals(StrategyEvaluationDecision.ROLLBACK_CONFIRMED, report.decision)
        assertEquals(StrategyEvaluationReason.STRATEGY_LATENCY_REGRESSION, report.reason)
        assertEquals(11L, report.latencyDeltaMillis)
        assertEquals(110L, report.allowedStrategyLatencyMillis)
    }

    @Test
    fun insufficientSamplesAreInconclusive() {
        val report = evaluator(
            StrategyEvaluationPolicy(requiredSuccessesPerPhase = 2),
        ).evaluate(
            strategyId = strategyId,
            baselineSamples = successes(10L, 20L),
            strategySamples = successes(10L),
            recoverySamples = successes(10L, 20L),
        )

        assertEquals(StrategyEvaluationDecision.INCONCLUSIVE, report.decision)
        assertEquals(StrategyEvaluationReason.STRATEGY_SAMPLES_INSUFFICIENT, report.reason)
    }

    @Test
    fun zeroBaselineAndLongMaxLatencyDoNotOverflow() {
        val zeroReport = evaluator(
            StrategyEvaluationPolicy(
                maxLatencyRegressionPercent = 50,
                maxAbsoluteLatencyRegressionMillis = 0L,
            ),
        ).evaluate(
            strategyId = strategyId,
            baselineSamples = successes(0L),
            strategySamples = successes(0L),
            recoverySamples = successes(0L),
        )
        assertEquals(0L, zeroReport.allowedStrategyLatencyMillis)
        assertEquals(StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION, zeroReport.decision)

        val maxReport = evaluator(
            StrategyEvaluationPolicy(
                maxLatencyRegressionPercent = 10_000,
                maxAbsoluteLatencyRegressionMillis = Long.MAX_VALUE,
            ),
        ).evaluate(
            strategyId = strategyId,
            baselineSamples = successes(Long.MAX_VALUE),
            strategySamples = successes(Long.MAX_VALUE),
            recoverySamples = successes(Long.MAX_VALUE),
        )
        assertEquals(Long.MAX_VALUE, maxReport.allowedStrategyLatencyMillis)
        assertEquals(StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION, maxReport.decision)
    }

    @Test
    fun evaluationRejectsUnboundedSampleLists() {
        val tooMany = List(101) { StrategyHealthSample.Success(1L) }
        assertThrows(IllegalArgumentException::class.java) {
            evaluator().evaluate(
                strategyId = strategyId,
                baselineSamples = tooMany,
                strategySamples = successes(1L),
                recoverySamples = successes(1L),
            )
        }
    }

    @Test
    fun phaseSummaryRejectsInconsistentFailureCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategyPhaseSummary(
                phase = StrategyHealthPhase.BASELINE,
                successes = 1,
                failures = 1,
                medianLatencyMillis = 10L,
                failureReasons = emptyMap(),
            )
        }
    }

    @Test
    fun reportRejectsForgedKeepDecision() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategyEvaluationReport(
                strategyId = strategyId,
                baseline = successfulSummary(StrategyHealthPhase.BASELINE),
                strategy = successfulSummary(StrategyHealthPhase.STRATEGY),
                recovery = successfulSummary(StrategyHealthPhase.RECOVERY),
                decision = StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION,
                reason = StrategyEvaluationReason.STRATEGY_SAMPLES_INSUFFICIENT,
                latencyDeltaMillis = null,
                allowedStrategyLatencyMillis = null,
            )
        }
    }

    @Test
    fun sessionGatePreventsImmediateFlapping() {
        val policy = StrategyEvaluationPolicy(cooldownMillis = 5_000L)
        val rollback = evaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(10L),
            strategySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.TIMEOUT),
            ),
            recoverySamples = successes(10L),
        )

        val evaluating = StrategySessionGate().begin(nowElapsedMillis = 1_000L)
        val coolingDown = evaluating.complete(
            report = rollback,
            nowElapsedMillis = 2_000L,
            policy = policy,
        )

        assertEquals(StrategySessionGateState.COOLDOWN, coolingDown.state)
        assertEquals(7_000L, coolingDown.cooldownUntilElapsedMillis)
        assertEquals(StrategyEvaluationDecision.ROLLBACK_CONFIRMED, coolingDown.lastDecision)
        assertEquals(StrategySessionGateState.COOLDOWN, coolingDown.refresh(6_999L).state)
        assertEquals(StrategySessionGateState.READY, coolingDown.refresh(7_000L).state)
        assertThrows(IllegalStateException::class.java) {
            coolingDown.begin(6_999L)
        }
    }

    @Test
    fun interruptedEvaluationEntersCooldown() {
        val policy = StrategyEvaluationPolicy(cooldownMillis = 5_000L)
        val aborted = StrategySessionGate()
            .begin(1_000L)
            .abort(
                nowElapsedMillis = 2_000L,
                policy = policy,
            )

        assertEquals(StrategySessionGateState.COOLDOWN, aborted.state)
        assertEquals(7_000L, aborted.cooldownUntilElapsedMillis)
        assertEquals(StrategyEvaluationDecision.INCONCLUSIVE, aborted.lastDecision)
        assertThrows(IllegalStateException::class.java) { aborted.begin(6_999L) }
        assertEquals(StrategySessionGateState.EVALUATING, aborted.begin(7_000L).state)
    }

    @Test
    fun cooldownDeadlineSaturatesInsteadOfWrapping() {
        val policy = StrategyEvaluationPolicy(cooldownMillis = 5_000L)
        val aborted = StrategySessionGate()
            .begin(Long.MAX_VALUE - 1_000L)
            .abort(
                nowElapsedMillis = Long.MAX_VALUE - 500L,
                policy = policy,
            )

        assertEquals(StrategySessionGateState.COOLDOWN, aborted.state)
        assertEquals(Long.MAX_VALUE, aborted.cooldownUntilElapsedMillis)
        assertEquals(StrategySessionGateState.COOLDOWN, aborted.refresh(Long.MAX_VALUE - 1L).state)
        assertEquals(StrategySessionGateState.READY, aborted.refresh(Long.MAX_VALUE).state)
    }

    @Test
    fun approvedSessionCanBeReevaluatedOnlyByNewExplicitBegin() {
        val keep = evaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(10L),
            strategySamples = successes(10L),
            recoverySamples = successes(10L),
        )
        val approved = StrategySessionGate()
            .begin(0L)
            .complete(keep, 1L)

        assertEquals(StrategySessionGateState.LAB_APPROVED, approved.state)
        val reevaluating = approved.begin(2L)
        assertEquals(StrategySessionGateState.EVALUATING, reevaluating.state)
        assertNull(reevaluating.lastDecision)
        assertEquals(StrategySessionGateState.READY, approved.resetApprovedSession().state)

        val disabled = approved.disable()
        assertEquals(StrategySessionGateState.DISABLED, disabled.state)
        assertFalse(disabled.refresh(Long.MAX_VALUE).state == StrategySessionGateState.READY)
        assertThrows(IllegalStateException::class.java) { disabled.begin(Long.MAX_VALUE) }
    }

    @Test
    fun gateStateAndDeadlineInvariantIsValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(
                state = StrategySessionGateState.READY,
                cooldownUntilElapsedMillis = 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(state = StrategySessionGateState.COOLDOWN)
        }
    }

    @Test
    fun policyBoundariesAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategyEvaluationPolicy(requiredSuccessesPerPhase = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrategyEvaluationPolicy(maxLatencyRegressionPercent = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrategyEvaluationPolicy(cooldownMillis = 999L)
        }
        assertTrue(StrategyEvaluationPolicy().cooldownMillis > 0L)
    }

    private fun evaluator(
        policy: StrategyEvaluationPolicy = StrategyEvaluationPolicy(),
    ) = StrategyHealthEvaluator(policy)

    private fun successes(vararg latencies: Long): List<StrategyHealthSample> =
        latencies.map(StrategyHealthSample::Success)

    private fun successfulSummary(phase: StrategyHealthPhase) = StrategyPhaseSummary(
        phase = phase,
        successes = 1,
        failures = 0,
        medianLatencyMillis = 10L,
        failureReasons = emptyMap(),
    )
}
