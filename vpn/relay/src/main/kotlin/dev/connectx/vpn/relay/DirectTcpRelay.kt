package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Authenticated local SOCKS5 endpoint that opens direct protected sockets.
 *
 * TCP CONNECT is always available. UDP ASSOCIATE is disabled unless both an
 * explicit [DatagramSocketProtector] and an allow-listed [UdpRelayTargetResolver]
 * are supplied by the caller.
 */
class DirectTcpRelay(
    private val socketProtector: SocketProtector,
    private val credentials: Socks5Credentials,
    private val targetResolver: RelayTargetResolver = RelayTargetResolver.IDENTITY,
    private val datagramSocketProtector: DatagramSocketProtector? = null,
    private val udpTargetResolver: UdpRelayTargetResolver? = null,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    maxConcurrentConnections: Int = DEFAULT_MAX_CONNECTIONS,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val connectionSlots = Semaphore(maxConcurrentConnections, true)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val activeDatagramSockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
    private val serverSocketReference = AtomicReference<ServerSocket?>(null)
    private val acceptThreadReference = AtomicReference<Thread?>(null)

    private val activeConnectionCount = AtomicLong(0)
    private val acceptedConnectionCount = AtomicLong(0)
    private val failedConnectionCount = AtomicLong(0)
    private val uploadedByteCount = AtomicLong(0)
    private val downloadedByteCount = AtomicLong(0)
    private val udpAssociationCount = AtomicLong(0)
    private val udpDatagramCount = AtomicLong(0)
    private val udpUploadedByteCount = AtomicLong(0)
    private val udpDownloadedByteCount = AtomicLong(0)

    @Volatile
    private var listeningPort: Int = 0

    init {
        require(maxConcurrentConnections in 1..256)
        require(
            (datagramSocketProtector == null) == (udpTargetResolver == null),
        ) {
            "UDP protector and target resolver must be supplied together"
        }
    }

    @Synchronized
    fun start(): Int {
        if (running.get()) return listeningPort

        val serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0))
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
        udpAssociations = udpAssociationCount.get(),
        udpDatagrams = udpDatagramCount.get(),
        udpUploadedBytes = udpUploadedByteCount.get(),
        udpDownloadedBytes = udpDownloadedByteCount.get(),
    )

    override fun close() {
        stop()
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        serverSocketReference.getAndSet(null).closeQuietly()
        activeSockets.toList().forEach { socket -> socket.closeQuietly() }
        activeSockets.clear()
        activeDatagramSockets.toList().forEach { socket -> socket.closeQuietly() }
        activeDatagramSockets.clear()

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
            Socks5Protocol.authenticateClient(
                input = input,
                output = output,
                credentials = credentials,
            )
            val request = Socks5Protocol.readRequest(input)
            requestAccepted = true

            when (request.command) {
                Socks5Protocol.COMMAND_CONNECT -> handleTcpConnect(
                    client = client,
                    output = output,
                    request = request,
                )

                Socks5Protocol.COMMAND_UDP_ASSOCIATE -> handleUdpAssociation(
                    client = client,
                    input = input,
                    output = output,
                )

                else -> throw UnsupportedCommandException(request.command)
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

    private fun handleTcpConnect(
        client: Socket,
        output: DataOutputStream,
        request: Socks5Request,
    ) {
        val target = try {
            targetResolver.resolve(request.host, request.port)
        } catch (error: RuntimeException) {
            throw IOException("Relay target resolver rejected the request", error)
        }

        val outbound = Socket()
        activeSockets += outbound
        try {
            outbound.tcpNoDelay = true
            if (!socketProtector.protect(outbound)) {
                throw IOException("Android не защитил исходящий TCP socket от возврата в TUN")
            }

            outbound.connect(
                InetSocketAddress(target.host, target.port),
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
    }

    private fun handleUdpAssociation(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        val protector = datagramSocketProtector
            ?: throw UnsupportedCommandException(Socks5Protocol.COMMAND_UDP_ASSOCIATE)
        val resolver = udpTargetResolver
            ?: throw UnsupportedCommandException(Socks5Protocol.COMMAND_UDP_ASSOCIATE)

        val associationSocket = DatagramSocket(null)
        val outboundSocket = DatagramSocket(null)
        activeDatagramSockets += associationSocket
        activeDatagramSockets += outboundSocket

        val controlClosed = AtomicBoolean(false)
        try {
            associationSocket.reuseAddress = true
            associationSocket.bind(
                InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0),
            )
            associationSocket.soTimeout = UDP_ASSOCIATION_POLL_MILLIS

            outboundSocket.reuseAddress = true
            outboundSocket.bind(
                InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0),
            )
            if (!protector.protect(outboundSocket)) {
                throw IOException("Android не защитил исходящий UDP socket от возврата в TUN")
            }
            outboundSocket.soTimeout = UDP_OUTBOUND_TIMEOUT_MILLIS

            Socks5Protocol.writeReply(
                output = output,
                replyCode = Socks5Protocol.REPLY_SUCCEEDED,
                bindHost = LOOPBACK_HOST,
                bindPort = associationSocket.localPort,
            )
            client.soTimeout = 0
            udpAssociationCount.incrementAndGet()

            daemonThread("connectx-relay-udp-control") {
                try {
                    while (input.read() >= 0) {
                        // A UDP association has no further TCP payload.
                    }
                } catch (_: IOException) {
                    // Closing the control connection terminates the association.
                } finally {
                    controlClosed.set(true)
                    associationSocket.closeQuietly()
                    outboundSocket.closeQuietly()
                }
            }.start()

            relayUdpDatagrams(
                associationSocket = associationSocket,
                outboundSocket = outboundSocket,
                resolver = resolver,
                controlClosed = controlClosed,
            )
        } finally {
            activeDatagramSockets -= associationSocket
            activeDatagramSockets -= outboundSocket
            associationSocket.closeQuietly()
            outboundSocket.closeQuietly()
        }
    }

    private fun relayUdpDatagrams(
        associationSocket: DatagramSocket,
        outboundSocket: DatagramSocket,
        resolver: UdpRelayTargetResolver,
        controlClosed: AtomicBoolean,
    ) {
        val receiveBuffer = ByteArray(MAX_SOCKS_UDP_DATAGRAM_BYTES)
        val responseBuffer = ByteArray(MAX_UDP_PAYLOAD_BYTES)
        var clientEndpoint: InetSocketAddress? = null
        var logicalTarget: RelayTarget? = null
        var protectedTarget: RelayTarget? = null

        while (running.get() && !controlClosed.get()) {
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
            try {
                associationSocket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: SocketException) {
                if (running.get() && !controlClosed.get()) throw error else return
            }

            val source = packet.socketAddress as? InetSocketAddress ?: continue
            if (!source.address.isLoopbackAddress) continue
            if (clientEndpoint == null) {
                clientEndpoint = source
            } else if (clientEndpoint != source) {
                continue
            }

            val datagram = Socks5Protocol.decodeUdpDatagram(
                bytes = packet.data,
                length = packet.length,
            )
            val destination = try {
                resolver.resolve(datagram.target.host, datagram.target.port)
            } catch (error: RuntimeException) {
                throw IOException("UDP target resolver rejected the datagram", error)
            }

            if (logicalTarget == null) {
                logicalTarget = datagram.target
                protectedTarget = destination
                outboundSocket.connect(
                    InetSocketAddress(destination.host, destination.port),
                )
            } else if (logicalTarget != datagram.target || protectedTarget != destination) {
                throw IOException("UDP association attempted to change its exact target")
            }

            outboundSocket.send(
                DatagramPacket(datagram.payload, datagram.payload.size),
            )
            udpUploadedByteCount.addAndGet(datagram.payload.size.toLong())

            val response = DatagramPacket(responseBuffer, responseBuffer.size)
            outboundSocket.receive(response)
            val payload = response.data.copyOfRange(
                response.offset,
                response.offset + response.length,
            )
            val framed = Socks5Protocol.encodeUdpDatagram(
                target = datagram.target,
                payload = payload,
            )
            associationSocket.send(
                DatagramPacket(framed, framed.size, clientEndpoint),
            )
            udpDatagramCount.incrementAndGet()
            udpDownloadedByteCount.addAndGet(payload.size.toLong())
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
    ): Thread = Thread({ block() }, name).apply {
        isDaemon = true
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_MAX_CONNECTIONS = 32
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        const val STOP_JOIN_TIMEOUT_MILLIS = 2_000L
        const val RELAY_WAIT_SLICE_MILLIS = 250L
        const val UDP_ASSOCIATION_POLL_MILLIS = 250
        const val UDP_OUTBOUND_TIMEOUT_MILLIS = 5_000
        const val MAX_SOCKS_UDP_DATAGRAM_BYTES = 65_535
        const val MAX_UDP_PAYLOAD_BYTES = 65_507
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

private fun DatagramSocket?.closeQuietly() {
    try {
        this?.close()
    } catch (_: RuntimeException) {
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
