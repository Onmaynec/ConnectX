package dev.connectx.vpn.relay

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local, payload-free stage counters for the bounded UDP probe.
 *
 * These counters are diagnostic only: they contain no addresses, credentials,
 * nonce bytes or traffic contents and are reset before each instrumentation run.
 */
object UdpProbeTrace {
    private val resolvedDatagrams = AtomicLong(0)
    private val echoReceives = AtomicLong(0)
    private val echoSends = AtomicLong(0)

    fun reset() {
        resolvedDatagrams.set(0)
        echoReceives.set(0)
        echoSends.set(0)
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
        resolvedDatagrams = resolvedDatagrams.get(),
        echoReceives = echoReceives.get(),
        echoSends = echoSends.get(),
    )

    data class Snapshot(
        val resolvedDatagrams: Long,
        val echoReceives: Long,
        val echoSends: Long,
    )
}
