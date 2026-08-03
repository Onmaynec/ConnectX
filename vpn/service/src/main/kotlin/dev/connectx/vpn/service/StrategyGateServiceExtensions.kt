package dev.connectx.vpn.service

import dev.connectx.strategy.api.StrategyEvaluationDecision
import dev.connectx.strategy.api.StrategyEvaluationPolicy
import dev.connectx.strategy.api.StrategySessionGate
import dev.connectx.strategy.api.StrategySessionGateState

internal fun StrategySessionGate.abort(
    nowElapsedMillis: Long,
    policy: StrategyEvaluationPolicy,
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
