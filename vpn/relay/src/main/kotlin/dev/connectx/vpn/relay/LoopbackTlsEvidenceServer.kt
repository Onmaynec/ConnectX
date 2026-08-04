package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Loopback-only deterministic responder for the external TLS evidence Android gate.
 *
 * It accepts one bounded TLS handshake record, verifies that its first handshake
 * message is ClientHello, and returns a fixed TLS alert record. It does not parse
 * SNI, perform a TLS handshake, open an outbound connection or retain payloads.
 */
class LoopbackTlsEvidenceServer(
    private val maxClientHelloBytes: Int = DEFAULT_MAX_CLIENT_HELLO_BYTES,
    private val socketTimeoutMillis: Int = DEFAULT_SOCKET_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val serverSocketReference = AtomicReference<ServerSocket?>(null)
    private val acceptThreadReference = AtomicReference<Thread?>(null)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val acceptedCount = AtomicLong(0)
    private val responseCount = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)

    @Volatile
    private var listeningPort: Int = 0

    init {
        require(maxClientHelloBytes in MIN_CLIENT_HELLO_BYTES..MAX_ALLOWED_CLIENT_HELLO_BYTES)
        require(socketTimeoutMillis in 100..10_000)
    }

    @Synchronized
    fun start(): Int {
        if (running.get()) return listeningPort

        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0))
        }
        serverSocketReference.set(server)
        listeningPort = server.localPort
        running.set(true)

        val thread = Thread(
            { acceptLoop(server) },
            "connectx-tls-evidence-responder",
        ).apply { isDaemon = true }
        acceptThreadReference.set(thread)
        thread.start()
        return listeningPort
    }

    fun port(): Int = listeningPort

    fun stats(): LoopbackTlsEvidenceStats = LoopbackTlsEvidenceStats(
        listeningPort = listeningPort,
        accepted = acceptedCount.get(),
        responses = responseCount.get(),
        rejected = rejectedCount.get(),
    )

    override fun close() {
        stop()
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        serverSocketReference.getAndSet(null).closeQuietlyForTlsEvidence()
        activeSockets.toList().forEach(Socket::closeQuietlyForTlsEvidence)
        activeSockets.clear()
        acceptThreadReference.getAndSet(null)?.let { thread ->
            if (thread !== Thread.currentThread()) {
                thread.join(STOP_JOIN_TIMEOUT_MILLIS)
            }
        }
        listeningPort = 0
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                if (running.get()) continue else return
            }

            activeSockets += socket
            Thread(
                {
                    try {
                        handleClient(socket)
                    } finally {
                        activeSockets -= socket
                        socket.closeQuietlyForTlsEvidence()
                    }
                },
                "connectx-tls-evidence-client",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        acceptedCount.incrementAndGet()
        socket.soTimeout = socketTimeoutMillis
        socket.tcpNoDelay = true

        try {
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(TLS_RECORD_HEADER_BYTES)
            input.readFully(header)

            val contentType = header.u8(0)
            val major = header.u8(1)
            val minor = header.u8(2)
            val recordLength = header.u16(3)
            if (
                contentType != TLS_HANDSHAKE_CONTENT_TYPE ||
                major != TLS_MAJOR_VERSION ||
                minor !in TLS_MINOR_VERSION_RANGE ||
                recordLength !in MIN_CLIENT_HELLO_BYTES..maxClientHelloBytes
            ) {
                rejectedCount.incrementAndGet()
                return
            }

            val record = ByteArray(recordLength)
            input.readFully(record)
            if (record.u8(0) != CLIENT_HELLO_HANDSHAKE_TYPE) {
                rejectedCount.incrementAndGet()
                return
            }

            val output = DataOutputStream(socket.getOutputStream())
            output.write(FIXED_ALERT_RECORD)
            output.flush()
            responseCount.incrementAndGet()
        } catch (_: EOFException) {
            rejectedCount.incrementAndGet()
        } catch (_: SocketTimeoutException) {
            rejectedCount.incrementAndGet()
        } catch (_: IOException) {
            if (running.get()) rejectedCount.incrementAndGet()
        }
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u16(offset: Int): Int =
        (u8(offset) shl 8) or u8(offset + 1)

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val TLS_RECORD_HEADER_BYTES = 5
        const val TLS_HANDSHAKE_CONTENT_TYPE = 22
        const val TLS_MAJOR_VERSION = 3
        val TLS_MINOR_VERSION_RANGE = 1..4
        const val CLIENT_HELLO_HANDSHAKE_TYPE = 1
        const val MIN_CLIENT_HELLO_BYTES = 39
        const val DEFAULT_MAX_CLIENT_HELLO_BYTES = 16 * 1024
        const val MAX_ALLOWED_CLIENT_HELLO_BYTES = 18_432
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 3_000
        const val STOP_JOIN_TIMEOUT_MILLIS = 1_000L

        val FIXED_ALERT_RECORD = byteArrayOf(
            21,
            3,
            3,
            0,
            2,
            2,
            40,
        )
    }
}

data class LoopbackTlsEvidenceStats(
    val listeningPort: Int,
    val accepted: Long,
    val responses: Long,
    val rejected: Long,
)

private fun Socket?.closeQuietlyForTlsEvidence() {
    try {
        this?.close()
    } catch (_: RuntimeException) {
        // Shutdown is best-effort and idempotent.
    }
}

private fun ServerSocket?.closeQuietlyForTlsEvidence() {
    try {
        this?.close()
    } catch (_: RuntimeException) {
        // Shutdown is best-effort and idempotent.
    }
}
