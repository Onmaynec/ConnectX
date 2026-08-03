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
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.nativebridge.NativeTunSession
import dev.connectx.vpn.relay.DirectTcpRelay
import dev.connectx.vpn.relay.ExactRelayTargetOverride
import dev.connectx.vpn.relay.LoopbackTcpEchoServer
import dev.connectx.vpn.relay.RelayStats
import dev.connectx.vpn.relay.RelayTarget
import dev.connectx.vpn.relay.RelayTargetResolver
import dev.connectx.vpn.relay.SocketProtector
import dev.connectx.vpn.relay.Socks5Credentials
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlin.math.max

class ConnectXTunnelService : VpnService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val secureRandom = SecureRandom()

    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var directTcpRelay: DirectTcpRelay? = null
    private var relayCredentials: Socks5Credentials? = null
    private var nativeTunSession: NativeTunSession? = null
    private var probeEchoServer: LoopbackTcpEchoServer? = null

    @Volatile
    private var probeSocket: Socket? = null

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
            val echoPort = if (mode == TunnelContract.MODE_NATIVE_TCP_PROBE) {
                startProbeEchoServer()
            } else {
                null
            }
            val relayPort = startDirectTcpRelay(echoPort)
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

            if (mode == TunnelContract.MODE_NATIVE_TCP_PROBE) {
                launchNativeTcpProbe()
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
        -> mode

        else -> error("Неизвестный режим локального движка")
    }

    private fun hasActiveResources(): Boolean =
        tunnelDescriptor != null ||
            directTcpRelay != null ||
            nativeTunSession != null ||
            probeEchoServer != null ||
            probeSocket != null

    private fun isModeAlreadyRunning(mode: String): Boolean {
        if (tunnelDescriptor == null || directTcpRelay == null || activeMode != mode) {
            return false
        }
        return mode == TunnelContract.MODE_FOUNDATION ||
            (nativeTunSession != null && runCatching { NativeTunBridge.isRunning() }.getOrDefault(false))
    }

    private fun establishTestTunnel(): ParcelFileDescriptor = Builder()
        .setSession("ConnectX v0.2 alpha TCP probe")
        .setMtu(DEFAULT_MTU)
        .addAddress(LOCAL_TUN_ADDRESS, LOCAL_TUN_PREFIX)
        // alpha.4 intentionally keeps only TEST-NET-1. Ordinary application
        // traffic cannot enter the unfinished native stack.
        .addRoute(TEST_ROUTE, TEST_ROUTE_PREFIX)
        .setBlocking(false)
        .establish()
        ?: error("Android не создал локальный TUN-интерфейс")

    private fun startProbeEchoServer(): Int {
        val server = LoopbackTcpEchoServer(maxPayloadBytes = PROBE_MAX_PAYLOAD_BYTES)
        val port = server.start()
        check(port in 1..65535) { "Loopback echo endpoint не открыл порт" }
        probeEchoServer = server
        return port
    }

    private fun startDirectTcpRelay(probeEchoPort: Int?): Int {
        directTcpRelay?.let { relay ->
            return relay.stats().listeningPort
        }

        val credentials = Socks5Credentials.random()
        val targetResolver = if (probeEchoPort == null) {
            RelayTargetResolver.IDENTITY
        } else {
            ExactRelayTargetOverride(
                source = RelayTarget(PROBE_TEST_HOST, PROBE_TEST_PORT),
                destination = RelayTarget(RELAY_HOST, probeEchoPort),
            )
        }
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector { socket -> protect(socket) },
            credentials = credentials,
            targetResolver = targetResolver,
        )
        val port = relay.start()
        check(port in 1..65535) { "Локальный TCP relay не открыл порт" }

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
        val generation = probeGeneration + 1L
        probeGeneration = generation
        val thread = Thread(
            {
                val result = runCatching { executeNativeTcpProbe() }
                mainHandler.post {
                    completeNativeTcpProbe(generation, result)
                }
            },
            "connectx-native-tcp-probe",
        ).apply {
            isDaemon = true
        }
        probeThread = thread
        thread.start()
    }

    private fun executeNativeTcpProbe(): TcpProbeResult {
        check(activeMode == TunnelContract.MODE_NATIVE_TCP_PROBE)
        check(NativeTunBridge.isRunning()) { "Native bridge остановился до TCP probe" }

        val payload = ByteArray(PROBE_PAYLOAD_BYTES).also(secureRandom::nextBytes)
        val socket = Socket()
        probeSocket = socket
        val startedAt = SystemClock.elapsedRealtimeNanos()

        try {
            socket.tcpNoDelay = true
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            // This socket is intentionally not protected. The TEST-NET route
            // must carry it into the Android TUN and userspace stack.
            socket.connect(
                InetSocketAddress(PROBE_TEST_HOST, PROBE_TEST_PORT),
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

            val latencyMillis = max(
                1L,
                (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND,
            )
            val stats = awaitProbeRelayStats(payload.size.toLong())
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
            if (probeSocket === socket) probeSocket = null
            runCatching { socket.close() }
        }
    }

    private fun awaitProbeRelayStats(payloadBytes: Long): RelayStats {
        val relay = checkNotNull(directTcpRelay) { "TCP relay отсутствует во время probe" }
        val deadline = SystemClock.elapsedRealtime() + PROBE_STATS_TIMEOUT_MILLIS
        var stats = relay.stats()
        while (
            (stats.uploadedBytes < payloadBytes || stats.downloadedBytes < payloadBytes) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
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
                probeResult = result,
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

    /** Closes probe client, native stack, Android TUN, relay, then echo endpoint. */
    private fun closeTunnelResources(): Throwable? {
        var firstError: Throwable? = null
        probeGeneration += 1L

        val activeProbeSocket = probeSocket
        probeSocket = null
        runCatching { activeProbeSocket?.close() }
            .onFailure { error -> firstError = error }
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

        val echoServer = probeEchoServer
        probeEchoServer = null
        runCatching { echoServer?.close() }
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
            TunnelContract.MODE_NATIVE_SELF_TEST ->
                "Native bridge активен · только TEST-NET"
            else -> "Защищённый TCP relay готов · тестовый маршрут"
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
        probeResult: TcpProbeResult? = null,
    ) {
        val statusIntent = Intent(TunnelContract.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(TunnelContract.EXTRA_STATUS, status)
            putExtra(TunnelContract.EXTRA_ENGINE_MODE, mode)
            putExtra(TunnelContract.EXTRA_NATIVE_ABI, currentAbi())
            nativeVersion?.let { putExtra(TunnelContract.EXTRA_NATIVE_VERSION, it) }
            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }
            probeResult?.let { result ->
                putExtra(
                    TunnelContract.EXTRA_PROBE_LATENCY_MILLIS,
                    result.latencyMillis,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_UPLOADED_BYTES,
                    result.uploadedBytes,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES,
                    result.downloadedBytes,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS,
                    result.relayConnections,
                )
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

        const val PROBE_TEST_HOST = "192.0.2.1"
        const val PROBE_TEST_PORT = 18_080
        const val PROBE_PAYLOAD_BYTES = 64
        const val PROBE_MAX_PAYLOAD_BYTES = 4 * 1024
        const val PROBE_TIMEOUT_MILLIS = 5_000
        const val PROBE_STATS_TIMEOUT_MILLIS = 1_000L
        const val PROBE_STATS_POLL_MILLIS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
