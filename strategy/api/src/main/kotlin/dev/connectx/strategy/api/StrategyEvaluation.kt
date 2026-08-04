package dev.connectx.strategy.api

import java.util.Collections
import java.util.EnumMap

/** A bounded, privacy-safe phase in a local strategy evaluation. */
enum class StrategyHealthPhase {
    BASELINE,
    STRATEGY,
    RECOVERY,
}

enum class StrategySampleFailure {
    TIMEOUT,
    CONNECTION_FAILED,
    PAYLOAD_MISMATCH,
    STRATEGY_REFUSED,
    CANCELLED,
    INTERNAL_ERROR,
}

sealed interface StrategyHealthSample {
    data class Success(
        val latencyMillis: Long,
    ) : StrategyHealthSample {
        init {
            require(latencyMillis >= 0L) { "Latency must not be negative" }
        }
    }

    data class Failure(
        val reason: StrategySampleFailure,
    ) : StrategyHealthSample
}

data class StrategyEvaluationPolicy(
    val requiredSuccessesPerPhase: Int = 1,
    val maxFailuresPerPhase: Int = 0,
    val maxLatencyRegressionPercent: Int = 50,
    val maxAbsoluteLatencyRegressionMillis: Long = 100L,
    val cooldownMillis: Long = 60_000L,
) {
    init {
        require(requiredSuccessesPerPhase in 1..100) {
            "Required successes must be between 1 and 100"
        }
        require(maxFailuresPerPhase in 0..100) {
            "Maximum failures must be between 0 and 100"
        }
        require(maxLatencyRegressionPercent in 0..10_000) {
            "Latency regression percentage must be between 0 and 10000"
        }
        require(maxAbsoluteLatencyRegressionMillis >= 0L) {
            "Absolute latency regression must not be negative"
        }
        require(cooldownMillis in 1_000L..86_400_000L) {
            "Cooldown must be between one second and one day"
        }
    }
}

data class StrategyPhaseSummary internal constructor(
    val phase: StrategyHealthPhase,
    val successes: Int,
    val failures: Int,
    val medianLatencyMillis: Long?,
    val failureReasons: Map<StrategySampleFailure, Int>,
) {
    init {
        require(successes >= 0) { "Success count must not be negative" }
        require(failures >= 0) { "Failure count must not be negative" }
        require(successes + failures <= MAX_PHASE_SAMPLES) {
            "A phase summary cannot exceed $MAX_PHASE_SAMPLES samples"
        }
        require((successes == 0) == (medianLatencyMillis == null)) {
            "Median latency must exist exactly when successes exist"
        }
        require(medianLatencyMillis == null || medianLatencyMillis >= 0L) {
            "Median latency must not be negative"
        }
        require(failureReasons.values.all { it > 0 }) {
            "Failure reason counts must be positive"
        }
        require(failureReasons.values.sum() == failures) {
            "Failure reason counts must equal total failures"
        }
    }

    val totalSamples: Int
        get() = successes + failures

    private companion object {
        const val MAX_PHASE_SAMPLES = 100
    }
}

enum class StrategyEvaluationDecision {
    KEEP_FOR_LAB_SESSION,
    ROLLBACK_CONFIRMED,
    REJECT_BASELINE_UNHEALTHY,
    REJECT_ENVIRONMENT_UNSTABLE,
    INCONCLUSIVE,
}

enum class StrategyEvaluationReason {
    PASSED_WITHIN_LATENCY_BUDGET,
    BASELINE_FAILURE_BUDGET_EXCEEDED,
    BASELINE_SAMPLES_INSUFFICIENT,
    STRATEGY_FAILURE_BUDGET_EXCEEDED,
    STRATEGY_SAMPLES_INSUFFICIENT,
    STRATEGY_LATENCY_REGRESSION,
    RECOVERY_FAILURE_BUDGET_EXCEEDED,
    RECOVERY_SAMPLES_INSUFFICIENT,
}

data class StrategyEvaluationReport internal constructor(
    val strategyId: StrategyId,
    val baseline: StrategyPhaseSummary,
    val strategy: StrategyPhaseSummary,
    val recovery: StrategyPhaseSummary,
    val decision: StrategyEvaluationDecision,
    val reason: StrategyEvaluationReason,
    val latencyDeltaMillis: Long?,
    val allowedStrategyLatencyMillis: Long?,
) {
    init {
        require(baseline.phase == StrategyHealthPhase.BASELINE) {
            "Baseline summary must use BASELINE phase"
        }
        require(strategy.phase == StrategyHealthPhase.STRATEGY) {
            "Strategy summary must use STRATEGY phase"
        }
        require(recovery.phase == StrategyHealthPhase.RECOVERY) {
            "Recovery summary must use RECOVERY phase"
        }
        require(
            (latencyDeltaMillis == null) ==
                (allowedStrategyLatencyMillis == null),
        ) {
            "Latency delta and allowed latency must be present together"
        }
        require(allowedStrategyLatencyMillis == null || allowedStrategyLatencyMillis >= 0L) {
            "Allowed strategy latency must not be negative"
        }

        val expectedDecision = when (reason) {
            StrategyEvaluationReason.PASSED_WITHIN_LATENCY_BUDGET ->
                StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION
            StrategyEvaluationReason.BASELINE_FAILURE_BUDGET_EXCEEDED ->
                StrategyEvaluationDecision.REJECT_BASELINE_UNHEALTHY
            StrategyEvaluationReason.BASELINE_SAMPLES_INSUFFICIENT ->
                StrategyEvaluationDecision.INCONCLUSIVE
            StrategyEvaluationReason.STRATEGY_FAILURE_BUDGET_EXCEEDED ->
                StrategyEvaluationDecision.ROLLBACK_CONFIRMED
            StrategyEvaluationReason.STRATEGY_SAMPLES_INSUFFICIENT ->
                StrategyEvaluationDecision.INCONCLUSIVE
            StrategyEvaluationReason.STRATEGY_LATENCY_REGRESSION ->
                StrategyEvaluationDecision.ROLLBACK_CONFIRMED
            StrategyEvaluationReason.RECOVERY_FAILURE_BUDGET_EXCEEDED ->
                StrategyEvaluationDecision.REJECT_ENVIRONMENT_UNSTABLE
            StrategyEvaluationReason.RECOVERY_SAMPLES_INSUFFICIENT ->
                StrategyEvaluationDecision.INCONCLUSIVE
        }
        require(decision == expectedDecision) {
            "Decision $decision is incompatible with reason $reason"
        }

        val requiresLatencyEvidence =
            reason == StrategyEvaluationReason.PASSED_WITHIN_LATENCY_BUDGET ||
                reason == StrategyEvaluationReason.STRATEGY_LATENCY_REGRESSION
        require((latencyDeltaMillis != null) == requiresLatencyEvidence) {
            "Latency evidence must exist exactly for passed or regressed strategy reports"
        }
    }
}

/**
 * Evaluates already-collected health samples. It never receives packet data,
 * host names, credentials or wall-clock identifiers.
 */
class StrategyHealthEvaluator(
    private val policy: StrategyEvaluationPolicy = StrategyEvaluationPolicy(),
) {
    fun evaluate(
        strategyId: StrategyId,
        baselineSamples: List<StrategyHealthSample>,
        strategySamples: List<StrategyHealthSample>,
        recoverySamples: List<StrategyHealthSample>,
    ): StrategyEvaluationReport {
        val baseline = summarize(StrategyHealthPhase.BASELINE, baselineSamples)
        val strategy = summarize(StrategyHealthPhase.STRATEGY, strategySamples)
        val recovery = summarize(StrategyHealthPhase.RECOVERY, recoverySamples)

        if (baseline.failures > policy.maxFailuresPerPhase) {
            return report(
                strategyId = strategyId,
                baseline = baseline,
                strategy = strategy,
                recovery = recovery,
                decision = StrategyEvaluationDecision.REJECT_BASELINE_UNHEALTHY,
                reason = StrategyEvaluationReason.BASELINE_FAILURE_BUDGET_EXCEEDED,
            )
        }
        if (baseline.successes < policy.requiredSuccessesPerPhase) {
            return report(
                strategyId = strategyId,
                baseline = baseline,
                strategy = strategy,
                recovery = recovery,
                decision = StrategyEvaluationDecision.INCONCLUSIVE,
                reason = StrategyEvaluationReason.BASELINE_SAMPLES_INSUFFICIENT,
            )
        }

        // Recovery is mandatory before keeping or blaming a strategy. A failed
        // recovery means the local environment itself is no longer stable.
        if (recovery.failures > policy.maxFailuresPerPhase) {
            return report(
                strategyId = strategyId,
                baseline = baseline,
                strategy = strategy,
                recovery = recovery,
                decision = StrategyEvaluationDecision.REJECT_ENVIRONMENT_UNSTABLE,
                reason = StrategyEvaluationReason.RECOVERY_FAILURE_BUDGET_EXCEEDED,
            )
        }
        if (recovery.successes < policy.requiredSuccessesPerPhase) {
            return report(
                strategyId = strategyId,
                baseline = baseline,
                strategy = strategy,
                recovery = recovery,
                decision = StrategyEvaluationDecision.INCONCLUSIVE,
                reason = StrategyEvaluationReason.RECOVERY_SAMPLES_INSUFFICIENT,
            )
        }

        if (strategy.failures > policy.maxFailuresPerPhase) {
            return report(
                strategyId = strategyId,
                baseline = baseline,
                strategy = strategy,
                recovery = recovery,
                decision = StrategyEvaluationDecision.ROLLBACK_CONFIRMED,
                reason = StrategyEvaluationReason.STRATEGY_FAILURE_BUDGET_EXCEEDED,
            )
        }
        if (strategy.successes < policy.requiredSuccessesPerPhase) {
            return report(
                strategyId = strategyId,
                baseline = baseline,
                strategy = strategy,
                recovery = recovery,
                decision = StrategyEvaluationDecision.INCONCLUSIVE,
                reason = StrategyEvaluationReason.STRATEGY_SAMPLES_INSUFFICIENT,
            )
        }

        val baselineLatency = checkNotNull(baseline.medianLatencyMillis)
        val strategyLatency = checkNotNull(strategy.medianLatencyMillis)
        val percentBudget = ceilPercentOf(
            value = baselineLatency,
            percent = policy.maxLatencyRegressionPercent,
        )
        val regressionBudget = maxOf(
            percentBudget,
            policy.maxAbsoluteLatencyRegressionMillis,
        )
        val allowedLatency = saturatingAdd(baselineLatency, regressionBudget)
        val delta = strategyLatency - baselineLatency

        return if (strategyLatency <= allowedLatency) {
            report(
                strategyId = strategyId,
                baseline = baseline,
                strategy = strategy,
                recovery = recovery,
                decision = StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION,
                reason = StrategyEvaluationReason.PASSED_WITHIN_LATENCY_BUDGET,
                latencyDeltaMillis = delta,
                allowedStrategyLatencyMillis = allowedLatency,
            )
        } else {
            report(
                strategyId = strategyId,
                baseline = baseline,
                strategy = strategy,
                recovery = recovery,
                decision = StrategyEvaluationDecision.ROLLBACK_CONFIRMED,
                reason = StrategyEvaluationReason.STRATEGY_LATENCY_REGRESSION,
                latencyDeltaMillis = delta,
                allowedStrategyLatencyMillis = allowedLatency,
            )
        }
    }

    private fun summarize(
        phase: StrategyHealthPhase,
        samples: List<StrategyHealthSample>,
    ): StrategyPhaseSummary {
        require(samples.size <= MAX_SAMPLES_PER_PHASE) {
            "A strategy phase cannot contain more than $MAX_SAMPLES_PER_PHASE samples"
        }
        val latencies = samples.mapNotNull { sample ->
            (sample as? StrategyHealthSample.Success)?.latencyMillis
        }.sorted()
        val failures = samples.mapNotNull { sample ->
            (sample as? StrategyHealthSample.Failure)?.reason
        }
        return StrategyPhaseSummary(
            phase = phase,
            successes = latencies.size,
            failures = failures.size,
            medianLatencyMillis = median(latencies),
            failureReasons = immutableFailureReasonCounts(failures),
        )
    }

    private fun immutableFailureReasonCounts(
        failures: List<StrategySampleFailure>,
    ): Map<StrategySampleFailure, Int> {
        val counts = EnumMap<StrategySampleFailure, Int>(StrategySampleFailure::class.java)
        failures.forEach { failure ->
            counts[failure] = (counts[failure] ?: 0) + 1
        }
        return Collections.unmodifiableMap(counts)
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val middle = values.size / 2
        if (values.size % 2 == 1) return values[middle]
        val lower = values[middle - 1]
        val upper = values[middle]
        return lower + ((upper - lower) / 2L)
    }

    private fun report(
        strategyId: StrategyId,
        baseline: StrategyPhaseSummary,
        strategy: StrategyPhaseSummary,
        recovery: StrategyPhaseSummary,
        decision: StrategyEvaluationDecision,
        reason: StrategyEvaluationReason,
        latencyDeltaMillis: Long? = null,
        allowedStrategyLatencyMillis: Long? = null,
    ): StrategyEvaluationReport = StrategyEvaluationReport(
        strategyId = strategyId,
        baseline = baseline,
        strategy = strategy,
        recovery = recovery,
        decision = decision,
        reason = reason,
        latencyDeltaMillis = latencyDeltaMillis,
        allowedStrategyLatencyMillis = allowedStrategyLatencyMillis,
    )

    private fun ceilPercentOf(value: Long, percent: Int): Long {
        val product = saturatingMultiply(value, percent.toLong())
        if (product == Long.MAX_VALUE) return Long.MAX_VALUE
        val quotient = product / PERCENT_DENOMINATOR
        return if (product % PERCENT_DENOMINATOR == 0L) {
            quotient
        } else {
            saturatingAdd(quotient, 1L)
        }
    }

    private fun saturatingMultiply(left: Long, right: Long): Long {
        if (left == 0L || right == 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val MAX_SAMPLES_PER_PHASE = 100
        const val PERCENT_DENOMINATOR = 100L
    }
}

enum class StrategySessionGateState {
    READY,
    EVALUATING,
    LAB_APPROVED,
    COOLDOWN,
    DISABLED,
}

/** Immutable session gate that prevents immediate re-evaluation and flapping. */
data class StrategySessionGate(
    val state: StrategySessionGateState = StrategySessionGateState.READY,
    val cooldownUntilElapsedMillis: Long? = null,
    val lastDecision: StrategyEvaluationDecision? = null,
) {
    init {
        require(
            (state == StrategySessionGateState.COOLDOWN) ==
                (cooldownUntilElapsedMillis != null),
        ) {
            "Only COOLDOWN state may carry a cooldown deadline"
        }
        require(cooldownUntilElapsedMillis == null || cooldownUntilElapsedMillis >= 0L) {
            "Cooldown deadline must not be negative"
        }
    }

    fun refresh(nowElapsedMillis: Long): StrategySessionGate {
        require(nowElapsedMillis >= 0L) { "Elapsed time must not be negative" }
        return if (
            state == StrategySessionGateState.COOLDOWN &&
            nowElapsedMillis >= checkNotNull(cooldownUntilElapsedMillis)
        ) {
            copy(
                state = StrategySessionGateState.READY,
                cooldownUntilElapsedMillis = null,
            )
        } else {
            this
        }
    }

    /** A new explicit user action may re-evaluate a previously approved lab session. */
    fun begin(nowElapsedMillis: Long): StrategySessionGate {
        val refreshed = refresh(nowElapsedMillis)
        check(
            refreshed.state == StrategySessionGateState.READY ||
                refreshed.state == StrategySessionGateState.LAB_APPROVED,
        ) {
            "Strategy evaluation cannot start from ${refreshed.state}"
        }
        return refreshed.copy(
            state = StrategySessionGateState.EVALUATING,
            cooldownUntilElapsedMillis = null,
            lastDecision = null,
        )
    }

    fun complete(
        report: StrategyEvaluationReport,
        nowElapsedMillis: Long,
        policy: StrategyEvaluationPolicy = StrategyEvaluationPolicy(),
    ): StrategySessionGate {
        require(nowElapsedMillis >= 0L) { "Elapsed time must not be negative" }
        check(state == StrategySessionGateState.EVALUATING) {
            "Strategy evaluation can only complete from EVALUATING"
        }
        return if (report.decision == StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION) {
            copy(
                state = StrategySessionGateState.LAB_APPROVED,
                cooldownUntilElapsedMillis = null,
                lastDecision = report.decision,
            )
        } else {
            copy(
                state = StrategySessionGateState.COOLDOWN,
                cooldownUntilElapsedMillis = saturatingDeadline(
                    nowElapsedMillis,
                    policy.cooldownMillis,
                ),
                lastDecision = report.decision,
            )
        }
    }

    fun abort(
        nowElapsedMillis: Long,
        policy: StrategyEvaluationPolicy = StrategyEvaluationPolicy(),
    ): StrategySessionGate {
        require(nowElapsedMillis >= 0L) { "Elapsed time must not be negative" }
        check(state == StrategySessionGateState.EVALUATING) {
            "Strategy evaluation can only abort from EVALUATING"
        }
        return copy(
            state = StrategySessionGateState.COOLDOWN,
            cooldownUntilElapsedMillis = saturatingDeadline(
                nowElapsedMillis,
                policy.cooldownMillis,
            ),
            lastDecision = StrategyEvaluationDecision.INCONCLUSIVE,
        )
    }

    fun resetApprovedSession(): StrategySessionGate {
        check(state == StrategySessionGateState.LAB_APPROVED) {
            "Only an approved lab session can be reset"
        }
        return copy(
            state = StrategySessionGateState.READY,
            cooldownUntilElapsedMillis = null,
        )
    }

    fun disable(): StrategySessionGate = copy(
        state = StrategySessionGateState.DISABLED,
        cooldownUntilElapsedMillis = null,
    )

    private fun saturatingDeadline(now: Long, cooldown: Long): Long =
        if (now > Long.MAX_VALUE - cooldown) Long.MAX_VALUE else now + cooldown
}
