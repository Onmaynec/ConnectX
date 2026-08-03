package dev.connectx.vpn.relay

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local, payload-free stage counters for the bounded UDP probe.
 *
 * These counters are diagnostic only: they contain no addresses, credentials,
 * nonce bytes or traffic contents and are reset before each instrumentation run.
 */
object UdpProbeTrace {
    private val associateRequests = AtomicLong(0)
    private val associationsReady = AtomicLong(0)
    private val relayPacketsReceived = AtomicLong(0)
    private val resolvedDatagrams = AtomicLong(0)
    private val echoReceives = AtomicLong(0)
    private val echoSends = AtomicLong(0)

    fun reset() {
        associateRequests.set(0)
        associationsReady.set(0)
        relayPacketsReceived.set(0)
        resolvedDatagrams.set(0)
        echoReceives.set(0)
        echoSends.set(0)
    }

    internal fun onAssociateRequest() {
        associateRequests.incrementAndGet()
    }

    internal fun onAssociationReady() {
        associationsReady.incrementAndGet()
    }

    internal fun onRelayPacketReceived() {
        relayPacketsReceived.incrementAndGet()
    }

    internal fun onDatagramResolved() {
        resolvedDatagrams.incrementAndGet()
    }

    internal fun onEchoReceived() {
        echoReceives.incrementAndGet()
    }

    internal fun onEchoSent() {
        echoSends.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        associateRequests = associateRequests.get(),
        associationsReady = associationsReady.get(),
        relayPacketsReceived = relayPacketsReceived.get(),
        resolvedDatagrams = resolvedDatagrams.get(),
        echoReceives = echoReceives.get(),
        echoSends = echoSends.get(),
    )

    data class Snapshot(
        val associateRequests: Long,
        val associationsReady: Long,
        val relayPacketsReceived: Long,
        val resolvedDatagrams: Long,
        val echoReceives: Long,
        val echoSends: Long,
    )
}
