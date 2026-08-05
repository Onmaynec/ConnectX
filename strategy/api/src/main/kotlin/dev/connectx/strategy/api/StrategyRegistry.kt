package dev.connectx.strategy.api

class StrategyRegistry(
    strategies: Collection<BypassStrategy>,
) {
    private val strategiesById: Map<StrategyId, BypassStrategy>

    init {
        val grouped = strategies.groupBy { it.descriptor.id }
        require(grouped.values.none { it.size > 1 }) {
            "Strategy ids must be unique"
        }
        strategiesById = grouped.mapValues { (_, values) -> values.single() }
    }

    fun descriptors(): List<StrategyDescriptor> =
        strategiesById.values
            .map(BypassStrategy::descriptor)
            .sortedBy { it.id.value }

    fun find(id: StrategyId): BypassStrategy? = strategiesById[id]

    companion object {
        fun labDefaults(): StrategyRegistry = StrategyRegistry(
            listOf(TlsClientHelloSplitStrategy()),
        )
    }
}
