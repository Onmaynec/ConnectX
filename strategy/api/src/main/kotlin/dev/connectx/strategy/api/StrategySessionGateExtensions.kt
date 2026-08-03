package dev.connectx.strategy.api

/** Moves an interrupted evaluation into the same bounded cooldown as a failed one. */
fun StrategySessionGate.abort(
    nowElapsedMillis: Long,
    policy: StrategyEvaluationPolicy = StrategyEvaluationPolicy(),
): StrategySessionGate {
    require(nowElapsedMillis >= 0L) { "Elapsed time must not be negative" }
    check(state == StrategySessionGateState.EVALUATING) {
        "Strategy evaluation can only abort from EVALUATING"
    }
    val deadline = if (nowElapsedMillis > Long.MAX_VALUE - policy.cooldownMillis) {
        Long.MAX_VALUE
    } else {
        nowElapsedMillis + policy.cooldownMillis
    }
    return copy(
        state = StrategySessionGateState.COOLDOWN,
        cooldownUntilElapsedMillis = deadline,
        lastDecision = StrategyEvaluationDecision.INCONCLUSIVE,
    )
}
