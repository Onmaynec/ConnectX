package dev.connectx.vpn.relay

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded loopback-only UDP echo endpoint for the alpha UDP path probe.
 *
 * The socket never binds to a LAN interface, accepts only loopback senders,
 * and drops datagrams that exceed the configured payload budget.
 */
class LoopbackUdpEchoServer(
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val receiveTimeoutMillis: Int = DEFAULT_RECEIVE_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val socketReference = AtomicReference<DatagramSocket?>(null)
    private val threadReference = AtomicReference<Thread?>(null)

    @Volatile
    private var listeningPort: Int = 0

    init {
        require(maxPayloadBytes in 1..MAX_ALLOWED_PAYLOAD_BYTES)
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
            { echoLoop(socket) },
            "connectx-probe-udp-echo",
        ).apply { isDaemon = true }
        threadReference.set(thread)
        thread.start()
        return listeningPort
    }

    fun port(): Int = listeningPort

    override fun close() {
        stop()
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        socketReference.getAndSet(null).closeQuietly()
        threadReference.getAndSet(null)?.let { thread ->
            if (thread !== Thread.currentThread()) {
                thread.join(STOP_JOIN_TIMEOUT_MILLIS)
            }
        }
        listeningPort = 0
    }

    private fun echoLoop(socket: DatagramSocket) {
        val buffer = ByteArray(maxPayloadBytes + 1)
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

            if (!packet.address.isLoopbackAddress || packet.length > maxPayloadBytes) {
                continue
            }
            UdpProbeTrace.onEchoReceived()

            try {
                socket.send(
                    DatagramPacket(
                        packet.data,
                        packet.offset,
                        packet.length,
                        packet.socketAddress,
                    ),
                )
                UdpProbeTrace.onEchoSent()
            } catch (_: IOException) {
                if (!running.get()) return
            }
        }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_MAX_PAYLOAD_BYTES = 4 * 1024
        const val MAX_ALLOWED_PAYLOAD_BYTES = 65_507
        const val DEFAULT_RECEIVE_TIMEOUT_MILLIS = 250
        const val STOP_JOIN_TIMEOUT_MILLIS = 1_000L
    }
}

private fun DatagramSocket?.closeQuietly() {
    try {
        this?.close()
    } catch (_: RuntimeException) {
        // Shutdown is best-effort and idempotent.
    }
}
