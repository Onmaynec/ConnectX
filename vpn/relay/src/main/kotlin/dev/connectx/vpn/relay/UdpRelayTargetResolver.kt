package dev.connectx.vpn.relay

/**
 * Resolves the protected outbound target for one authenticated SOCKS5 UDP datagram.
 *
 * Alpha diagnostics intentionally do not expose an identity/default resolver.
 * UDP forwarding is enabled only when the caller supplies an exact allow-listed
 * TEST-NET override.
 */
fun interface UdpRelayTargetResolver {
    fun resolve(host: String, port: Int): RelayTarget
}

/**
 * Allows one exact logical UDP endpoint and rejects every other destination.
 */
class ExactUdpRelayTargetOverride(
    private val source: RelayTarget,
    private val destination: RelayTarget,
) : UdpRelayTargetResolver {
    override fun resolve(host: String, port: Int): RelayTarget {
        if (host == source.host && port == source.port) {
            UdpProbeTrace.onDatagramResolved()
            return destination
        }
        throw IllegalArgumentException(
            "UDP target is outside the exact diagnostic allow-list",
        )
    }
}
