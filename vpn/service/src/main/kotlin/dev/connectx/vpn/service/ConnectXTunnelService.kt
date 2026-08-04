package dev.connectx.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dev.connectx.strategy.api.ApplicationProtocol
import dev.connectx.strategy.api.LabTlsClientHello
import dev.connectx.strategy.api.NetworkProtocol
import dev.connectx.strategy.api.StrategyContext
import dev.connectx.strategy.api.StrategyFeatureGate
import dev.connectx.strategy.api.StrategyPlan
import dev.connectx.strategy.api.StrategyScope
import dev.connectx.strategy.api.TlsClientHelloSplitStrategy
import dev.connectx.strategy.api.TransportProtocol
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.nativebridge.NativeTunSession
import dev.connectx.vpn.relay.DatagramSocketProtector
import dev.connectx.vpn.relay.DirectTcpRelay
import dev.connectx.vpn.relay.ExactRelayTargetOverride
import dev.connectx.vpn.relay.ExactUdpRelayTargetOverride
import dev.connectx.vpn.relay.LoopbackTcpEchoServer
import dev.connectx.vpn.relay.LoopbackUdpEchoServer
import dev.connectx.vpn.relay.RelayStats
import dev.connectx.vpn.relay.RelayTarget
import dev.connectx.vpn.relay.RelayTargetResolver
import dev.connectx.vpn.relay.SocketProtector
import dev.connectx.vpn.relay.Socks5Credentials
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.math.max

class ConnectXTunnelService : VpnService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val secureRandom = SecureRandom()

    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var directTcpRelay: DirectTcpRelay? = null
    private var relayCredentials: Socks5Credentials? = null
    private var nativeTunSession: NativeTunSession? = null
    private var tcpProbeEchoServer: LoopbackTcpEchoServer? = null
    private var udpProbeEchoServer: LoopbackUdpEchoServer? = null

    @Volatile
    private var tcpProbeSocket: Socket? = null

    @Volatile
    private var udpProbeSocket: DatagramSocket? = null

    @Volatile
    private var probeGeneration: Long = 0L

    private var probeThread: Thread? = null
    private var activeMode: String = TunnelContract.MODE_FOUNDATION
    private var nativeVersion: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TunnelContract.ACTION_STOP -> stopTunnelAndService()
            TunnelContract.ACTION_START, null -> startTunnel(
                requestedMode = intent
                    ?.getStringExtra(TunnelContract.EXTRA_ENGINE_MODE)
                    .orEmpty()
                    .ifBlank { TunnelContract.MODE_FOUNDATION },
            )
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnelAndService()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnelResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startTunnel(requestedMode: String) {
        val mode = validateMode(requestedMode)
        promoteToForeground(mode)

        if (isModeAlreadyRunning(mode)) {
            publishStartedStatus()
            return
        }

        if (hasActiveResources()) {
            closeTunnelResources()
        }

        try {
            val tcpEchoPort = if (
                mode == TunnelContract.MODE_NATIVE_TCP_PROBE ||
                mode == TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE
            ) {
                startTcpProbeEchoServer()
            } else {
                null
            }
            val tcpProbeSource = when (mode) {
                TunnelContract.MODE_NATIVE_TCP_PROBE ->
                    RelayTarget(TCP_PROBE_TEST_HOST, TCP_PROBE_TEST_PORT)
                TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE ->
                    RelayTarget(TLS_SPLIT_TEST_HOST, TLS_SPLIT_TEST_PORT)
                else -> null
            }
            val udpEchoPort = if (mode == TunnelContract.MODE_NATIVE_UDP_PROBE) {
                startUdpProbeEchoServer()
            } else {
                null
            }
            val relayPort = startDirectRelay(
                tcpProbeEchoPort = tcpEchoPort,
                tcpProbeSource = tcpProbeSource,
                udpProbeEchoPort = udpEchoPort,
            )
            val tunnel = establishTestTunnel()
            tunnelDescriptor = tunnel

            var startedNativeVersion: String? = null
            if (mode != TunnelContract.MODE_FOUNDATION) {
                startedNativeVersion = startNativeSession(
                    tunnel = tunnel,
                    relayPort = relayPort,
                )
            }

            activeMode = mode
            nativeVersion = startedNativeVersion
            publishStartedStatus()
            updateForegroundNotification()

            when (mode) {
                TunnelContract.MODE_NATIVE_TCP_PROBE -> launchNativeTcpProbe()
                TunnelContract.MODE_NATIVE_UDP_PROBE -> launchNativeUdpProbe()
                TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE -> launchNativeTlsSplitProbe()
            }
        } catch (error: Throwable) {
            val cleanupError = closeTunnelResources()
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                mode = mode,
                error = buildFailureMessage(error, cleanupError),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun validateMode(mode: String): String = when (mode) {
        TunnelContract.MODE_FOUNDATION,
        TunnelContract.MODE_NATIVE_SELF_TEST,
        TunnelContract.MODE_NATIVE_TCP_PROBE,
        TunnelContract.MODE_NATIVE_UDP_PROBE,
        TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE,
        -> mode

        else -> error("Неизвестный режим локального движка")
    }

    private fun hasActiveResources(): Boolean =
        tunnelDescriptor != null ||
            directTcpRelay != null ||
            nativeTunSession != null ||
            tcpProbeEchoServer != null ||
            udpProbeEchoServer != null ||
            tcpProbeSocket != null ||
            udpProbeSocket != null

    private fun isModeAlreadyRunning(mode: String): Boolean {
        if (tunnelDescriptor == null || directTcpRelay == null || activeMode != mode) {
            return false
        }
        return mode == TunnelContract.MODE_FOUNDATION ||
            (nativeTunSession != null && runCatching { NativeTunBridge.isRunning() }.getOrDefault(false))
    }

    private fun establishTestTunnel(): ParcelFileDescriptor = Builder()
        .setSession("ConnectX v0.3 alpha diagnostics")
        .setMtu(DEFAULT_MTU)
        .addAddress(LOCAL_TUN_ADDRESS, LOCAL_TUN_PREFIX)
        // Strategy alpha intentionally keeps only TEST-NET-1. Ordinary
        // application traffic cannot enter the lab strategy path.
        .addRoute(TEST_ROUTE, TEST_ROUTE_PREFIX)
        .setBlocking(false)
        .establish()
        ?: error("Android не создал локальный TUN-интерфейс")

    private fun startTcpProbeEchoServer(): Int {
        val server = LoopbackTcpEchoServer(maxPayloadBytes = PROBE_MAX_PAYLOAD_BYTES)
        val port = server.start()
        check(port in 1..65535) { "Loopback TCP echo endpoint не открыл порт" }
        tcpProbeEchoServer = server
        return port
    }

    private fun startUdpProbeEchoServer(): Int {
        val server = LoopbackUdpEchoServer(maxPayloadBytes = PROBE_MAX_PAYLOAD_BYTES)
        val port = server.start()
        check(port in 1..65535) { "Loopback UDP echo endpoint не открыл порт" }
        udpProbeEchoServer = server
        return port
    }

    private fun startDirectRelay(
        tcpProbeEchoPort: Int?,
        tcpProbeSource: RelayTarget?,
        udpProbeEchoPort: Int?,
    ): Int {
        directTcpRelay?.let { relay ->
            return relay.stats().listeningPort
        }

        val credentials = Socks5Credentials.random()
        val tcpTargetResolver = if (tcpProbeEchoPort == null || tcpProbeSource == null) {
            RelayTargetResolver.IDENTITY
        } else {
            ExactRelayTargetOverride(
                source = tcpProbeSource,
                destination = RelayTarget(RELAY_HOST, tcpProbeEchoPort),
            )
        }
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector { socket -> protect(socket) },
            credentials = credentials,
            targetResolver = tcpTargetResolver,
            datagramSocketProtector = udpProbeEchoPort?.let {
                DatagramSocketProtector { socket -> protect(socket) }
            },
            udpTargetResolver = udpProbeEchoPort?.let { echoPort ->
                ExactUdpRelayTargetOverride(
                    source = RelayTarget(UDP_PROBE_TEST_HOST, UDP_PROBE_TEST_PORT),
                    destination = RelayTarget(RELAY_HOST, echoPort),
                )
            },
        )
        val port = relay.start()
        check(port in 1..65535) { "Локальный SOCKS5 relay не открыл порт" }

        relayCredentials = credentials
        directTcpRelay = relay
        return port
    }

    private fun startNativeSession(
        tunnel: ParcelFileDescriptor,
        relayPort: Int,
    ): String {
        check(NativeTunBridge.isAvailable()) {
            NativeTunBridge.loadError()
                ?: "Native bridge недоступен для ABI этого устройства"
        }

        val version = NativeTunBridge.version().getOrElse { error ->
            throw IllegalStateException(
                "Не удалось выполнить JNI version self-check",
                error,
            )
        }
        val credentials = checkNotNull(relayCredentials) {
            "Отсутствуют временные credentials локального relay"
        }

        val session = NativeTunSession()
        try {
            session.start(
                tunnel = tunnel,
                mtu = DEFAULT_MTU,
                relayHost = RELAY_HOST,
                relayPort = relayPort,
                relayUsername = credentials.username,
                relayPassword = credentials.password,
            )
            check(NativeTunBridge.isRunning()) {
                NativeTunBridge.lastError().ifBlank {
                    "Native bridge не подтвердил состояние running"
                }
            }
            nativeTunSession = session
            return version
        } catch (error: Throwable) {
            runCatching { session.close() }
            throw error
        }
    }

    private fun launchNativeTcpProbe() {
        val generation = nextProbeGeneration()
        val thread = Thread(
            {
                val result = runCatching { executeNativeTcpProbe() }
                mainHandler.post {
                    completeNativeTcpProbe(generation, result)
                }
            },
            "connectx-native-tcp-probe",
        ).apply { isDaemon = true }
        probeThread = thread
        thread.start()
    }

    private fun executeNativeTcpProbe(): TcpProbeResult {
        check(activeMode == TunnelContract.MODE_NATIVE_TCP_PROBE)
        check(NativeTunBridge.isRunning()) { "Native bridge остановился до TCP probe" }

        val payload = ByteArray(PROBE_PAYLOAD_BYTES).also(secureRandom::nextBytes)
        val socket = Socket()
        tcpProbeSocket = socket
        val startedAt = SystemClock.elapsedRealtimeNanos()

        try {
            socket.tcpNoDelay = true
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            // This socket is intentionally not protected. The TEST-NET route
            // must carry it into the Android TUN and userspace stack.
            socket.connect(
                InetSocketAddress(TCP_PROBE_TEST_HOST, TCP_PROBE_TEST_PORT),
                PROBE_TIMEOUT_MILLIS,
            )
            socket.getOutputStream().apply {
                write(payload)
                flush()
            }

            val echoed = ByteArray(payload.size)
            DataInputStream(socket.getInputStream()).readFully(echoed)
            check(payload.contentEquals(echoed)) {
                "TCP probe получил несовпадающий echo nonce"
            }

            val latencyMillis = elapsedMillisSince(startedAt)
            val stats = awaitTcpProbeRelayStats(payload.size.toLong())
            check(stats.acceptedConnections >= 1L) {
                "Relay не подтвердил TCP probe соединение"
            }
            check(stats.uploadedBytes >= payload.size.toLong()) {
                "Relay не подтвердил отправленные байты TCP probe"
            }
            check(stats.downloadedBytes >= payload.size.toLong()) {
                "Relay не подтвердил полученные байты TCP probe"
            }

            return TcpProbeResult(
                latencyMillis = latencyMillis,
                uploadedBytes = stats.uploadedBytes,
                downloadedBytes = stats.downloadedBytes,
                relayConnections = stats.acceptedConnections,
            )
        } finally {
            if (tcpProbeSocket === socket) tcpProbeSocket = null
            runCatching { socket.close() }
        }
    }

    private fun launchNativeTlsSplitProbe() {
        val generation = nextProbeGeneration()
        val thread = Thread(
            {
                val result = runCatching { executeNativeTlsSplitProbe() }
                mainHandler.post {
                    completeNativeTlsSplitProbe(generation, result)
                }
            },
            "connectx-native-tls-split-probe",
        ).apply { isDaemon = true }
        probeThread = thread
        thread.start()
    }

    private fun executeNativeTlsSplitProbe(): StrategyProbeResult {
        check(activeMode == TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE)
        check(NativeTunBridge.isRunning()) {
            "Native bridge остановился до TLS split probe"
        }

        val strategy = TlsClientHelloSplitStrategy()
        val payload = buildSyntheticClientHello()
        val plan = strategy.plan(
            payload = payload,
            context = StrategyContext(
                transport = TransportProtocol.TCP,
                network = NetworkProtocol.IPV4,
                application = ApplicationProtocol.TLS,
                scope = StrategyScope.LAB_ONLY,
            ),
            featureGate = StrategyFeatureGate(
                globallyEnabled = true,
                enabledStrategies = setOf(strategy.descriptor.id),
            ),
        )
        check(plan is StrategyPlan.Segmented) {
            "TLS split strategy отказалась от валидного lab ClientHello: $plan"
        }
        check(payload.contentEquals(plan.reconstruct())) {
            "TLS split strategy изменила reconstructed ClientHello"
        }

        val socket = Socket()
        tcpProbeSocket = socket
        val startedAt = SystemClock.elapsedRealtimeNanos()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            // The socket is intentionally unprotected so the exact TEST-NET
            // target traverses Android TUN and the native userspace stack.
            socket.connect(
                InetSocketAddress(TLS_SPLIT_TEST_HOST, TLS_SPLIT_TEST_PORT),
                PROBE_TIMEOUT_MILLIS,
            )
            val output = socket.getOutputStream()
            plan.segments.forEachIndexed { index, segment ->
                output.write(segment)
                output.flush()
                if (index + 1 < plan.segments.size) {
                    Thread.sleep(TLS_SPLIT_WRITE_GAP_MILLIS)
                }
            }

            val echoed = ByteArray(payload.size)
            DataInputStream(socket.getInputStream()).readFully(echoed)
            check(payload.contentEquals(echoed)) {
                "TLS split probe получил изменённый reconstructed ClientHello"
            }

            val latencyMillis = elapsedMillisSince(startedAt)
            val stats = awaitTcpProbeRelayStats(payload.size.toLong())
            check(stats.acceptedConnections >= 1L) {
                "Relay не подтвердил TLS split probe соединение"
            }
            check(stats.uploadedBytes >= payload.size.toLong()) {
                "Relay не подтвердил отправленные байты TLS split probe"
            }
            check(stats.downloadedBytes >= payload.size.toLong()) {
                "Relay не подтвердил полученные байты TLS split probe"
            }

            return StrategyProbeResult(
                strategyId = strategy.descriptor.id.value,
                segments = plan.segments.size,
                splitOffset = plan.splitOffset,
                latencyMillis = latencyMillis,
                uploadedBytes = stats.uploadedBytes,
                downloadedBytes = stats.downloadedBytes,
                relayConnections = stats.acceptedConnections,
            )
        } finally {
            if (tcpProbeSocket === socket) tcpProbeSocket = null
            runCatching { socket.close() }
        }
    }

    private fun buildSyntheticClientHello(): ByteArray {
        val randomBytes = ByteArray(LabTlsClientHello.RANDOM_BYTES)
            .also(secureRandom::nextBytes)
        return LabTlsClientHello.create(randomBytes)
    }

    private fun launchNativeUdpProbe() {
        val generation = nextProbeGeneration()
        val thread = Thread(
            {
                val result = runCatching { executeNativeUdpProbe() }
                mainHandler.post {
                    completeNativeUdpProbe(generation, result)
                }
            },
            "connectx-native-udp-probe",
        ).apply { isDaemon = true }
        probeThread = thread
        thread.start()
    }

    private fun executeNativeUdpProbe(): UdpProbeResult {
        check(activeMode == TunnelContract.MODE_NATIVE_UDP_PROBE)
        check(NativeTunBridge.isRunning()) { "Native bridge остановился до UDP probe" }

        val payload = ByteArray(PROBE_PAYLOAD_BYTES).also(secureRandom::nextBytes)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        Thread.sleep(UDP_ROUTE_SETTLE_MILLIS)
        var lastTimeout: SocketTimeoutException? = null

        repeat(UDP_PROBE_ATTEMPTS) { attempt ->
            check(activeMode == TunnelContract.MODE_NATIVE_UDP_PROBE) {
                "UDP probe был отменён"
            }
            check(NativeTunBridge.isRunning()) {
                "Native bridge остановился во время UDP probe"
            }

            val socket = DatagramSocket(null)
            udpProbeSocket = socket
            try {
                socket.reuseAddress = true
                // Explicit Inet4Address prevents Android from choosing an IPv6
                // wildcard socket that cannot use the IPv4-only TEST-NET route.
                socket.bind(
                    InetSocketAddress(
                        InetAddress.getByName(IPV4_ANY_HOST),
                        0,
                    ),
                )
                socket.soTimeout = UDP_PROBE_ATTEMPT_TIMEOUT_MILLIS
                // This socket is intentionally not protected. The TEST-NET route
                // must carry the UDP datagram into Android TUN and the native stack.
                socket.connect(
                    InetSocketAddress(UDP_PROBE_TEST_HOST, UDP_PROBE_TEST_PORT),
                )
                socket.send(DatagramPacket(payload, payload.size))

                val responseBuffer = ByteArray(payload.size + 1)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                val echoed = response.data.copyOfRange(
                    response.offset,
                    response.offset + response.length,
                )
                check(payload.contentEquals(echoed)) {
                    "UDP probe получил несовпадающий echo nonce"
                }

                val latencyMillis = elapsedMillisSince(startedAt)
                val stats = awaitUdpProbeRelayStats(payload.size.toLong())
                check(stats.udpAssociations >= 1L) {
                    "Relay не подтвердил authenticated UDP association"
                }
                check(stats.udpDatagrams >= 1L) {
                    "Relay не подтвердил UDP datagram exchange"
                }
                check(stats.udpUploadedBytes >= payload.size.toLong()) {
                    "Relay не подтвердил отправленные байты UDP probe"
                }
                check(stats.udpDownloadedBytes >= payload.size.toLong()) {
                    "Relay не подтвердил полученные байты UDP probe"
                }

                return UdpProbeResult(
                    latencyMillis = latencyMillis,
                    uploadedBytes = stats.udpUploadedBytes,
                    downloadedBytes = stats.udpDownloadedBytes,
                    relayAssociations = stats.udpAssociations,
                    datagrams = stats.udpDatagrams,
                )
            } catch (error: SocketTimeoutException) {
                lastTimeout = error
            } finally {
                if (udpProbeSocket === socket) udpProbeSocket = null
                runCatching { socket.close() }
            }

            if (attempt + 1 < UDP_PROBE_ATTEMPTS) {
                Thread.sleep(UDP_PROBE_RETRY_DELAY_MILLIS)
            }
        }

        throw IOException(
            "UDP probe не получил echo после $UDP_PROBE_ATTEMPTS IPv4 попыток; " +
                NativeTunBridge.transportDiagnostics(),
            lastTimeout,
        )
    }

    private fun nextProbeGeneration(): Long {
        val generation = probeGeneration + 1L
        probeGeneration = generation
        return generation
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long = max(
        1L,
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLISECOND,
    )

    private fun awaitTcpProbeRelayStats(payloadBytes: Long): RelayStats =
        awaitRelayStats { stats ->
            stats.uploadedBytes >= payloadBytes && stats.downloadedBytes >= payloadBytes
        }

    private fun awaitUdpProbeRelayStats(payloadBytes: Long): RelayStats =
        awaitRelayStats { stats ->
            stats.udpAssociations >= 1L &&
                stats.udpDatagrams >= 1L &&
                stats.udpUploadedBytes >= payloadBytes &&
                stats.udpDownloadedBytes >= payloadBytes
        }

    private fun awaitRelayStats(predicate: (RelayStats) -> Boolean): RelayStats {
        val relay = checkNotNull(directTcpRelay) { "SOCKS5 relay отсутствует во время probe" }
        val deadline = SystemClock.elapsedRealtime() + PROBE_STATS_TIMEOUT_MILLIS
        var stats = relay.stats()
        while (!predicate(stats) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(PROBE_STATS_POLL_MILLIS)
            stats = relay.stats()
        }
        return stats
    }

    private fun completeNativeTcpProbe(
        generation: Long,
        outcome: Result<TcpProbeResult>,
    ) {
        if (
            generation != probeGeneration ||
            activeMode != TunnelContract.MODE_NATIVE_TCP_PROBE
        ) {
            return
        }

        val completedVersion = nativeVersion
        val cleanupError = closeTunnelResources()
        val result = outcome.getOrNull()
        if (result != null && cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_PROBE_SUCCEEDED,
                mode = TunnelContract.MODE_NATIVE_TCP_PROBE,
                nativeVersion = completedVersion,
                tcpProbeResult = result,
            )
        } else {
            val primary = outcome.exceptionOrNull()
                ?: IllegalStateException("TCP probe завершён, но ресурсы закрылись с ошибкой")
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                mode = TunnelContract.MODE_NATIVE_TCP_PROBE,
                error = buildFailureMessage(primary, cleanupError),
                nativeVersion = completedVersion,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun completeNativeTlsSplitProbe(
        generation: Long,
        outcome: Result<StrategyProbeResult>,
    ) {
        if (
            generation != probeGeneration ||
            activeMode != TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE
        ) {
            return
        }

        val completedVersion = nativeVersion
        val cleanupError = closeTunnelResources()
        val result = outcome.getOrNull()
        if (result != null && cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_STRATEGY_PROBE_SUCCEEDED,
                mode = TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE,
                nativeVersion = completedVersion,
                strategyProbeResult = result,
            )
        } else {
            val primary = outcome.exceptionOrNull()
                ?: IllegalStateException(
                    "TLS split probe завершён, но ресурсы закрылись с ошибкой",
                )
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                mode = TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE,
                error = buildFailureMessage(primary, cleanupError),
                nativeVersion = completedVersion,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun completeNativeUdpProbe(
        generation: Long,
        outcome: Result<UdpProbeResult>,
    ) {
        if (
            generation != probeGeneration ||
            activeMode != TunnelContract.MODE_NATIVE_UDP_PROBE
        ) {
            return
        }

        val completedVersion = nativeVersion
        val cleanupError = closeTunnelResources()
        val result = outcome.getOrNull()
        if (result != null && cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_UDP_PROBE_SUCCEEDED,
                mode = TunnelContract.MODE_NATIVE_UDP_PROBE,
                nativeVersion = completedVersion,
                udpProbeResult = result,
            )
        } else {
            val primary = outcome.exceptionOrNull()
                ?: IllegalStateException("UDP probe завершён, но ресурсы закрылись с ошибкой")
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                mode = TunnelContract.MODE_NATIVE_UDP_PROBE,
                error = buildFailureMessage(primary, cleanupError),
                nativeVersion = completedVersion,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTunnelAndService() {
        val stoppedMode = activeMode
        val cleanupError = closeTunnelResources()
        if (cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_STOPPED,
                mode = stoppedMode,
            )
        } else {
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                mode = stoppedMode,
                error = "Ресурсы остановлены с ошибкой: ${cleanupError.message ?: cleanupError::class.java.simpleName}",
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Closes probe clients, native stack, Android TUN, relay, then echo endpoints. */
    private fun closeTunnelResources(): Throwable? {
        var firstError: Throwable? = null
        probeGeneration += 1L

        val activeTcpProbeSocket = tcpProbeSocket
        tcpProbeSocket = null
        runCatching { activeTcpProbeSocket?.close() }
            .onFailure { error -> firstError = error }

        val activeUdpProbeSocket = udpProbeSocket
        udpProbeSocket = null
        runCatching { activeUdpProbeSocket?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }
        probeThread = null

        val nativeSession = nativeTunSession
        nativeTunSession = null
        runCatching { nativeSession?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        val descriptor = tunnelDescriptor
        tunnelDescriptor = null
        try {
            descriptor?.close()
        } catch (error: IOException) {
            if (firstError == null) firstError = error
        }

        val relay = directTcpRelay
        directTcpRelay = null
        relayCredentials = null
        runCatching { relay?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        val tcpEchoServer = tcpProbeEchoServer
        tcpProbeEchoServer = null
        runCatching { tcpEchoServer?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        val udpEchoServer = udpProbeEchoServer
        udpProbeEchoServer = null
        runCatching { udpEchoServer?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        nativeVersion = null
        activeMode = TunnelContract.MODE_FOUNDATION
        return firstError
    }

    private fun buildFailureMessage(
        primary: Throwable,
        cleanup: Throwable?,
    ): String {
        val primaryMessage = primary.message ?: primary::class.java.simpleName
        return if (cleanup == null) {
            primaryMessage
        } else {
            "$primaryMessage; ошибка очистки: ${cleanup.message ?: cleanup::class.java.simpleName}"
        }
    }

    private fun promoteToForeground(mode: String) {
        val notification = buildNotification(mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(activeMode))
    }

    private fun buildNotification(mode: String): Notification {
        val stopIntent = Intent(this, ConnectXTunnelService::class.java).apply {
            action = TunnelContract.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val detail = when (mode) {
            TunnelContract.MODE_NATIVE_TCP_PROBE ->
                "Проверка TCP через TEST-NET TUN"
            TunnelContract.MODE_NATIVE_UDP_PROBE ->
                "Проверка UDP через TEST-NET TUN"
            TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE ->
                "Lab TLS write-split через TEST-NET TUN"
            TunnelContract.MODE_NATIVE_SELF_TEST ->
                "Native bridge активен · только TEST-NET"
            else -> "Защищённый SOCKS5 relay готов · тестовый маршрут"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("ConnectX")
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Выключить", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Работа ConnectX",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Состояние локальной обработки сетевого трафика"
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun publishStartedStatus() {
        publishStatus(
            status = TunnelContract.STATUS_STARTED,
            mode = activeMode,
            nativeVersion = nativeVersion,
        )
    }

    private fun publishStatus(
        status: String,
        mode: String,
        error: String? = null,
        nativeVersion: String? = null,
        tcpProbeResult: TcpProbeResult? = null,
        udpProbeResult: UdpProbeResult? = null,
        strategyProbeResult: StrategyProbeResult? = null,
    ) {
        val statusIntent = Intent(TunnelContract.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(TunnelContract.EXTRA_STATUS, status)
            putExtra(TunnelContract.EXTRA_ENGINE_MODE, mode)
            putExtra(TunnelContract.EXTRA_NATIVE_ABI, currentAbi())
            nativeVersion?.let { putExtra(TunnelContract.EXTRA_NATIVE_VERSION, it) }
            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }
            tcpProbeResult?.let { result ->
                putExtra(TunnelContract.EXTRA_PROBE_LATENCY_MILLIS, result.latencyMillis)
                putExtra(TunnelContract.EXTRA_PROBE_UPLOADED_BYTES, result.uploadedBytes)
                putExtra(TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES, result.downloadedBytes)
                putExtra(TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS, result.relayConnections)
            }
            udpProbeResult?.let { result ->
                putExtra(TunnelContract.EXTRA_PROBE_LATENCY_MILLIS, result.latencyMillis)
                putExtra(TunnelContract.EXTRA_PROBE_UPLOADED_BYTES, result.uploadedBytes)
                putExtra(TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES, result.downloadedBytes)
                putExtra(TunnelContract.EXTRA_PROBE_RELAY_ASSOCIATIONS, result.relayAssociations)
                putExtra(TunnelContract.EXTRA_PROBE_DATAGRAMS, result.datagrams)
            }
            strategyProbeResult?.let { result ->
                putExtra(TunnelContract.EXTRA_STRATEGY_ID, result.strategyId)
                putExtra(TunnelContract.EXTRA_STRATEGY_SEGMENTS, result.segments)
                putExtra(TunnelContract.EXTRA_STRATEGY_SPLIT_OFFSET, result.splitOffset)
                putExtra(TunnelContract.EXTRA_PROBE_LATENCY_MILLIS, result.latencyMillis)
                putExtra(TunnelContract.EXTRA_PROBE_UPLOADED_BYTES, result.uploadedBytes)
                putExtra(TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES, result.downloadedBytes)
                putExtra(TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS, result.relayConnections)
            }
        }
        sendBroadcast(statusIntent)
    }

    private fun currentAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI

    private data class TcpProbeResult(
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayConnections: Long,
    )

    private data class UdpProbeResult(
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayAssociations: Long,
        val datagrams: Long,
    )

    private data class StrategyProbeResult(
        val strategyId: String,
        val segments: Int,
        val splitOffset: Int,
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayConnections: Long,
    )

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "connectx_tunnel"
        const val NOTIFICATION_ID = 1001
        const val STOP_REQUEST_CODE = 1002

        const val DEFAULT_MTU = 1500
        const val LOCAL_TUN_ADDRESS = "10.222.0.2"
        const val LOCAL_TUN_PREFIX = 32
        const val TEST_ROUTE = "192.0.2.0"
        const val TEST_ROUTE_PREFIX = 24
        const val RELAY_HOST = "127.0.0.1"
        const val IPV4_ANY_HOST = "0.0.0.0"

        const val TCP_PROBE_TEST_HOST = "192.0.2.1"
        const val TCP_PROBE_TEST_PORT = 18_080
        const val UDP_PROBE_TEST_HOST = "192.0.2.1"
        const val UDP_PROBE_TEST_PORT = 18_081
        const val TLS_SPLIT_TEST_HOST = "192.0.2.1"
        const val TLS_SPLIT_TEST_PORT = 18_443
        const val PROBE_PAYLOAD_BYTES = 64
        const val PROBE_MAX_PAYLOAD_BYTES = 4 * 1024
        const val PROBE_TIMEOUT_MILLIS = 5_000
        const val UDP_ROUTE_SETTLE_MILLIS = 300L
        const val UDP_PROBE_ATTEMPTS = 3
        const val UDP_PROBE_ATTEMPT_TIMEOUT_MILLIS = 1_500
        const val UDP_PROBE_RETRY_DELAY_MILLIS = 150L
        const val TLS_SPLIT_WRITE_GAP_MILLIS = 25L
        const val PROBE_STATS_TIMEOUT_MILLIS = 1_000L
        const val PROBE_STATS_POLL_MILLIS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
