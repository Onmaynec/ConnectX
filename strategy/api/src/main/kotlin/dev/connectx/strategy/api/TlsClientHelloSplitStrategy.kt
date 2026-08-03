package dev.connectx.strategy.api

class TlsClientHelloSplitStrategy : BypassStrategy {
    override val descriptor = StrategyDescriptor(
        id = ID,
        displayName = "TLS ClientHello split (Lab)",
        description = "Plans two ordered socket writes inside a validated TLS ClientHello.",
        capabilities = setOf(
            StrategyCapability.TCP,
            StrategyCapability.IPV4,
            StrategyCapability.TLS,
        ),
        requiresRoot = false,
        reversible = true,
    )

    override fun plan(
        payload: ByteArray,
        context: StrategyContext,
        featureGate: StrategyFeatureGate,
    ): StrategyPlan {
        if (context.alreadyPlanned) {
            return StrategyPlan.Refused(StrategyRefusalReason.ALREADY_PLANNED)
        }
        if (
            context.transport != TransportProtocol.TCP ||
            context.network != NetworkProtocol.IPV4 ||
            context.application != ApplicationProtocol.TLS
        ) {
            return StrategyPlan.Refused(StrategyRefusalReason.UNSUPPORTED_CONTEXT)
        }
        if (context.scope != StrategyScope.LAB_ONLY) {
            return StrategyPlan.Refused(StrategyRefusalReason.OUTSIDE_ALLOWED_SCOPE)
        }
        if (!featureGate.globallyEnabled || descriptor.id !in featureGate.enabledStrategies) {
            return StrategyPlan.Refused(StrategyRefusalReason.FEATURE_DISABLED)
        }
        if (!featureGate.allows(descriptor, context)) {
            return StrategyPlan.Refused(StrategyRefusalReason.OUTSIDE_ALLOWED_SCOPE)
        }

        return when (val inspection = TlsClientHelloInspector.inspect(payload)) {
            is TlsClientHelloInspector.Result.Invalid -> {
                StrategyPlan.Refused(inspection.reason)
            }

            is TlsClientHelloInspector.Result.Valid -> {
                StrategyPlan.Segmented.of(
                    payload = payload,
                    splitOffset = inspection.metadata.splitOffset,
                )
            }
        }
    }

    companion object {
        val ID = StrategyId("tls-clienthello-split-v1")
    }
}
