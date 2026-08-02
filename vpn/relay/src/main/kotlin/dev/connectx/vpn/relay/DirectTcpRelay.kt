package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Local SOCKS5 CONNECT endpoint that opens direct protected TCP sockets.
 *
 * This class never connects to a ConnectX server. It is an internal bridge
 * between a future tun2socks instance and the real destination socket.
 */
class DirectTcpRelay(
    private val socketProtector: SocketProtector,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    maxConcurrentConnections: Int = DEFAULT_MAX_CONNECTIONS,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val connectionSlots = Semaphore(maxConcurrentConnections, true)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val serverSocketReference = AtomicReference<ServerSocket?>(null)
    private val acceptThreadReference = AtomicReference<Thread?>(null)

    private val activeConnectionCount = AtomicLong(0)
    private val acceptedConnectionCount = AtomicLong(0)
    private val failedConnectionCount = AtomicLong(0)
    private val uploadedByteCount = AtomicLong(0)
    private val downloadedByteCount = AtomicLong(0)

    @Volatile
    private var listeningPort: Int = 0

    @Synchronized
    fun start(): Int {
        if (running.get()) return listeningPort

        val serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }

        serverSocketReference.set(serverSocket)
        listeningPort = serverSocket.localPort
        running.set(true)

        val acceptThread = daemonThread("connectx-relay-accept") {
            acceptLoop(serverSocket)
        }
        acceptThreadReference.set(acceptThread)
        acceptThread.start()

        return listeningPort
    }

    fun stats(): RelayStats = RelayStats(
        listeningPort = listeningPort,
        activeConnections = activeConnectionCount.get(),
        acceptedConnections = acceptedConnectionCount.get(),
        failedConnections = failedConnectionCount.get(),
        uploadedBytes = uploadedByteCount.get(),
        downloadedBytes = downloadedByteCount.get(),
    )

    override fun close() {
        stop()
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        serverSocketReference.getAndSet(null).closeQuietly()
        activeSockets.toList().forEach(Socket::closeQuietly)
        activeSockets.clear()

        acceptThreadReference.getAndSet(null)?.let { thread ->
            if (thread !== Thread.currentThread()) {
                thread.join(STOP_JOIN_TIMEOUT_MILLIS)
            }
        }

        listeningPort = 0
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running.get()) {
            val client = try {
                serverSocket.accept()
            } catch (error: SocketException) {
                if (running.get()) failedConnectionCount.incrementAndGet()
                break
            } catch (_: IOException) {
                failedConnectionCount.incrementAndGet()
                continue
            }

            if (!connectionSlots.tryAcquire()) {
                failedConnectionCount.incrementAndGet()
                client.closeQuietly()
                continue
            }

            acceptedConnectionCount.incrementAndGet()
            activeConnectionCount.incrementAndGet()
            activeSockets += client

            daemonThread("connectx-relay-client") {
                try {
                    handleClient(client)
                } finally {
                    activeSockets -= client
                    client.closeQuietly()
                    activeConnectionCount.decrementAndGet()
                    connectionSlots.release()
                }
            }.start()
        }
    }

    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS

        val input = DataInputStream(client.getInputStream())
        val output = DataOutputStream(client.getOutputStream())
        var requestAccepted = false

        try {
            Socks5Protocol.negotiateAuthentication(input, output)
            val request = Socks5Protocol.readConnectRequest(input)
            requestAccepted = true

            val outbound = Socket()
            activeSockets += outbound
            try {
                outbound.tcpNoDelay = true
                if (!socketProtector.protect(outbound)) {
                    throw IOException("Android не защитил исходящий сокет от возврата в TUN")
                }

                outbound.connect(
                    InetSocketAddress(request.host, request.port),
                    connectTimeoutMillis,
                )

                client.soTimeout = 0
                outbound.soTimeout = 0
                Socks5Protocol.writeReply(output, Socks5Protocol.REPLY_SUCCEEDED)
                relayBidirectionally(client, outbound)
            } finally {
                activeSockets -= outbound
                outbound.closeQuietly()
            }
        } catch (error: UnsupportedCommandException) {
            failedConnectionCount.incrementAndGet()
            Socks5Protocol.writeReplySafely(
                output = output,
                replyCode = Socks5Protocol.REPLY_COMMAND_NOT_SUPPORTED,
            )
        } catch (error: UnsupportedAddressTypeException) {
            failedConnectionCount.incrementAndGet()
            Socks5Protocol.writeReplySafely(
                output = output,
                replyCode = Socks5Protocol.REPLY_ADDRESS_TYPE_NOT_SUPPORTED,
            )
        } catch (_: IOException) {
            failedConnectionCount.incrementAndGet()
            if (requestAccepted) {
                Socks5Protocol.writeReplySafely(
                    output = output,
                    replyCode = Socks5Protocol.REPLY_GENERAL_FAILURE,
                )
            }
        }
    }

    private fun relayBidirectionally(
        client: Socket,
        outbound: Socket,
    ) {
        val finished = CountDownLatch(2)

        daemonThread("connectx-relay-upload") {
            try {
                copyStream(
                    input = client.getInputStream(),
                    output = outbound.getOutputStream(),
                    byteCounter = uploadedByteCount,
                )
                outbound.shutdownOutputQuietly()
            } finally {
                finished.countDown()
            }
        }.start()

        daemonThread("connectx-relay-download") {
            try {
                copyStream(
                    input = outbound.getInputStream(),
                    output = client.getOutputStream(),
                    byteCounter = downloadedByteCount,
                )
                client.shutdownOutputQuietly()
            } finally {
                finished.countDown()
            }
        }.start()

        while (running.get()) {
            if (finished.await(RELAY_WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
                return
            }
        }
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        byteCounter: AtomicLong,
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        try {
            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) return
                if (read == 0) continue

                output.write(buffer, 0, read)
                output.flush()
                byteCounter.addAndGet(read.toLong())
            }
        } catch (_: IOException) {
            // Closing either side is the normal way to cancel a blocking relay read.
        }
    }

    private fun daemonThread(
        name: String,
        block: () -> Unit,
    ): Thread = Thread(block, name).apply {
        isDaemon = true
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_MAX_CONNECTIONS = 32
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        const val STOP_JOIN_TIMEOUT_MILLIS = 2_000L
        const val RELAY_WAIT_SLICE_MILLIS = 250L
        const val COPY_BUFFER_SIZE = 32 * 1024
    }
}

private fun Socket?.closeQuietly() {
    try {
        this?.close()
    } catch (_: IOException) {
        // Shutdown is best-effort and idempotent.
    }
}

private fun Socket.shutdownOutputQuietly() {
    try {
        shutdownOutput()
    } catch (_: IOException) {
        // The peer may already have closed the socket.
    }
}

private fun ServerSocket?.closeQuietly() {
    try {
        this?.close()
    } catch (_: IOException) {
        // Shutdown is best-effort and idempotent.
    }
}

private fun Socks5Protocol.writeReplySafely(
    output: DataOutputStream,
    replyCode: Int,
) {
    try {
        writeReply(output, replyCode)
    } catch (_: IOException) {
        // The client may have disconnected before receiving the error reply.
    }
}
