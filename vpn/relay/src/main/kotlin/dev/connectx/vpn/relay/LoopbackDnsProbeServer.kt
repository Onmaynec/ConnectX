package dev.connectx.vpn.relay

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic loopback-only DNS responder for the bounded alpha DNS probe.
 *
 * It never forwards a request. Only the exact query accepted by
 * [DnsProbeProtocol] receives the fixed TEST-NET answer.
 */
class LoopbackDnsProbeServer(
    private val receiveTimeoutMillis: Int = DEFAULT_RECEIVE_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val socketReference = AtomicReference<DatagramSocket?>(null)
    private val threadReference = AtomicReference<Thread?>(null)
    private val queryCount = AtomicLong(0)
    private val responseCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)

    @Volatile
    private var listeningPort: Int = 0

    init {
        require(receiveTimeoutMillis in 100..10_000)
    }

    @Synchronized
    fun start(): Int {
        if (running.get()) return listeningPort

        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0))
            soTimeout = receiveTimeoutMillis
        }
        socketReference.set(socket)
        listeningPort = socket.localPort
        running.set(true)

        val thread = Thread(
            { respondLoop(socket) },
            "connectx-probe-dns-responder",
        ).apply { isDaemon = true }
        threadReference.set(thread)
        thread.start()
        return listeningPort
    }

    fun port(): Int = listeningPort

    fun stats(): DnsProbeServerStats = DnsProbeServerStats(
        listeningPort = listeningPort,
        queries = queryCount.get(),
        responses = responseCount.get(),
        rejected = rejectedCount.get(),
    )

    override fun close() {
        stop()
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        socketReference.getAndSet(null).closeQuietlyForDnsProbe()
        threadReference.getAndSet(null)?.let { thread ->
            if (thread !== Thread.currentThread()) {
                thread.join(STOP_JOIN_TIMEOUT_MILLIS)
            }
        }
        listeningPort = 0
    }

    private fun respondLoop(socket: DatagramSocket) {
        val buffer = ByteArray(DnsProbeProtocol.MAX_PACKET_BYTES + 1)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                if (running.get()) continue else return
            }

            if (!packet.address.isLoopbackAddress || packet.length > DnsProbeProtocol.MAX_PACKET_BYTES) {
                rejectedCount.incrementAndGet()
                continue
            }
            queryCount.incrementAndGet()

            val response = try {
                DnsProbeProtocol.buildResponse(packet.data, packet.length)
            } catch (_: IllegalArgumentException) {
                rejectedCount.incrementAndGet()
                continue
            }

            try {
                socket.send(
                    DatagramPacket(
                        response,
                        response.size,
                        packet.socketAddress,
                    ),
                )
                responseCount.incrementAndGet()
            } catch (_: IOException) {
                if (!running.get()) return
            }
        }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_RECEIVE_TIMEOUT_MILLIS = 250
        const val STOP_JOIN_TIMEOUT_MILLIS = 1_000L
    }
}

data class DnsProbeServerStats(
    val listeningPort: Int,
    val queries: Long,
    val responses: Long,
    val rejected: Long,
)

private fun DatagramSocket?.closeQuietlyForDnsProbe() {
    try {
        this?.close()
    } catch (_: RuntimeException) {
        // Shutdown is best-effort and idempotent.
    }
}
