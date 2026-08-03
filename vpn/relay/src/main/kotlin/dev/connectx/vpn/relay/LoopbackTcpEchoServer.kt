package dev.connectx.vpn.relay

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Small loopback-only echo endpoint used by the bounded alpha TCP path probe.
 *
 * It never binds to a LAN interface and closes a connection after the bounded
 * payload budget is exceeded or the peer becomes idle.
 */
class LoopbackTcpEchoServer(
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val socketTimeoutMillis: Int = DEFAULT_SOCKET_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val serverReference = AtomicReference<ServerSocket?>(null)
    private val acceptThreadReference = AtomicReference<Thread?>(null)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var listeningPort: Int = 0

    init {
        require(maxPayloadBytes in 1..MAX_ALLOWED_PAYLOAD_BYTES)
        require(socketTimeoutMillis in 250..60_000)
    }

    @Synchronized
    fun start(): Int {
        if (running.get()) return listeningPort

        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }
        serverReference.set(server)
        listeningPort = server.localPort
        running.set(true)

        val acceptThread = daemonThread("connectx-probe-echo-accept") {
            acceptLoop(server)
        }
        acceptThreadReference.set(acceptThread)
        acceptThread.start()
        return listeningPort
    }

    fun port(): Int = listeningPort

    override fun close() {
        stop()
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        serverReference.getAndSet(null).closeQuietly()
        activeSockets.toList().forEach(Socket::closeQuietly)
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
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                if (running.get()) continue else return
            }

            activeSockets += client
            daemonThread("connectx-probe-echo-client") {
                try {
                    echo(client)
                } finally {
                    activeSockets -= client
                    client.closeQuietly()
                }
            }.start()
        }
    }

    private fun echo(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = socketTimeoutMillis

        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0

        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) return
            if (read == 0) continue

            total += read
            if (total > maxPayloadBytes) {
                throw IOException("Probe payload exceeded $maxPayloadBytes bytes")
            }
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    private fun daemonThread(name: String, block: () -> Unit): Thread =
        Thread({ block() }, name).apply { isDaemon = true }

    private companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES = 4 * 1024
        const val MAX_ALLOWED_PAYLOAD_BYTES = 64 * 1024
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 5_000
        const val COPY_BUFFER_SIZE = 1024
        const val STOP_JOIN_TIMEOUT_MILLIS = 1_000L
    }
}

private fun Socket?.closeQuietly() {
    try {
        this?.close()
    } catch (_: IOException) {
        // Shutdown is best-effort and idempotent.
    }
}

private fun ServerSocket?.closeQuietly() {
    try {
        this?.close()
    } catch (_: IOException) {
        // Shutdown is best-effort and idempotent.
    }
}
