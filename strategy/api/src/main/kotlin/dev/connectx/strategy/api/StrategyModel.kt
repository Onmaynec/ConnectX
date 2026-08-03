package dev.connectx.strategy.api

@JvmInline
value class StrategyId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) {
            "Strategy id must use lowercase ASCII letters, digits and hyphens"
        }
    }

    override fun toString(): String = value

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    }
}

enum class StrategyCapability {
    TCP,
    UDP,
    IPV4,
    IPV6,
    TLS,
    QUIC,
    ROOT,
}

enum class TransportProtocol {
    TCP,
    UDP,
}

enum class NetworkProtocol {
    IPV4,
    IPV6,
}

enum class ApplicationProtocol {
    TLS,
    QUIC,
    UNKNOWN,
}

enum class StrategyScope {
    LAB_ONLY,
    USER_TRAFFIC,
}

data class StrategyDescriptor(
    val id: StrategyId,
    val displayName: String,
    val description: String,
    val capabilities: Set<StrategyCapability>,
    val requiresRoot: Boolean,
    val reversible: Boolean,
)

data class StrategyContext(
    val transport: TransportProtocol,
    val network: NetworkProtocol,
    val application: ApplicationProtocol,
    val scope: StrategyScope,
    val rootAvailable: Boolean = false,
    val alreadyPlanned: Boolean = false,
)

data class StrategyFeatureGate(
    val globallyEnabled: Boolean = false,
    val enabledStrategies: Set<StrategyId> = emptySet(),
    val allowUserTraffic: Boolean = false,
) {
    fun allows(descriptor: StrategyDescriptor, context: StrategyContext): Boolean {
        if (!globallyEnabled || descriptor.id !in enabledStrategies) return false
        if (descriptor.requiresRoot && !context.rootAvailable) return false
        if (context.scope == StrategyScope.USER_TRAFFIC && !allowUserTraffic) return false
        return true
    }
}

enum class StrategyRefusalReason {
    FEATURE_DISABLED,
    OUTSIDE_ALLOWED_SCOPE,
    ROOT_UNAVAILABLE,
    ALREADY_PLANNED,
    UNSUPPORTED_CONTEXT,
    PAYLOAD_TOO_SMALL,
    PAYLOAD_TOO_LARGE,
    NOT_TLS_HANDSHAKE,
    NOT_CLIENT_HELLO,
    TRUNCATED_RECORD,
    MALFORMED_LENGTH,
}

sealed interface StrategyPlan {
    data class Refused(
        val reason: StrategyRefusalReason,
    ) : StrategyPlan

    class Segmented private constructor(
        segments: List<ByteArray>,
        val splitOffset: Int,
    ) : StrategyPlan {
        val segments: List<ByteArray> = segments.map(ByteArray::copyOf)

        init {
            require(this.segments.size >= 2) { "A segmented plan requires at least two segments" }
            require(this.segments.none { it.isEmpty() }) { "Segments must not be empty" }
            require(splitOffset > 0) { "Split offset must be positive" }
        }

        fun reconstruct(): ByteArray {
            val totalSize = segments.sumOf(ByteArray::size)
            val output = ByteArray(totalSize)
            var offset = 0
            segments.forEach { segment ->
                segment.copyInto(output, destinationOffset = offset)
                offset += segment.size
            }
            return output
        }

        companion object {
            fun of(payload: ByteArray, splitOffset: Int): Segmented {
                require(splitOffset in 1 until payload.size) {
                    "Split offset must be inside the payload"
                }
                return Segmented(
                    segments = listOf(
                        payload.copyOfRange(0, splitOffset),
                        payload.copyOfRange(splitOffset, payload.size),
                    ),
                    splitOffset = splitOffset,
                )
            }
        }
    }
}

interface BypassStrategy {
    val descriptor: StrategyDescriptor

    fun plan(
        payload: ByteArray,
        context: StrategyContext,
        featureGate: StrategyFeatureGate,
    ): StrategyPlan
}
