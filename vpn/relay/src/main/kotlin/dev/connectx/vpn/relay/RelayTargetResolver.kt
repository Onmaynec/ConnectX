package dev.connectx.vpn.relay

/** A validated TCP target selected after an authenticated SOCKS5 request. */
data class RelayTarget(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "Relay target host is blank" }
        require(port in 1..65535) { "Relay target port is invalid: $port" }
    }
}

/**
 * Resolves the actual protected outbound target for one authenticated request.
 *
 * Production traffic uses [IDENTITY]. Alpha diagnostics may use an exact,
 * explicitly bounded override for a reserved TEST-NET endpoint.
 */
fun interface RelayTargetResolver {
    fun resolve(host: String, port: Int): RelayTarget

    companion object {
        val IDENTITY: RelayTargetResolver = RelayTargetResolver { host, port ->
            RelayTarget(host = host, port = port)
        }
    }
}

/** Rewrites one exact source endpoint and leaves every other target untouched. */
class ExactRelayTargetOverride(
    private val source: RelayTarget,
    private val destination: RelayTarget,
) : RelayTargetResolver {
    override fun resolve(host: String, port: Int): RelayTarget =
        if (host == source.host && port == source.port) {
            destination
        } else {
            RelayTarget(host = host, port = port)
        }
}
