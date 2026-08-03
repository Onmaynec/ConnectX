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
import dev.connectx.vpn.relay.DatagramSocketProtector
import dev.connectx.vpn.relay.DirectTcpRelay
import dev.connectx.vpn.relay.DnsProbeProtocol
import dev.connectx.vpn.relay.ExactUdpRelayTargetOverride
import dev.connectx.vpn.relay.LoopbackDnsProbeServer
import dev.connectx.vpn.relay.RelayStats
import dev.connectx.vpn.relay.RelayTarget
import dev.connectx.vpn.relay.RelayTargetResolver
import dev.connectx.vpn.relay.SocketProtector
import dev.connectx.vpn.relay.Socks5Credentials
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.math.max

/**
 * Isolated foreground VpnService for the bounded alpha.6 DNS path probe.
 *
 * It never installs a default route, never forwards to an external resolver,
 * and accepts only the exact TEST-NET DNS endpoint used by the probe.
 */
class ConnectXDnsProbeService : VpnService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val secureRandom = SecureRandom()

    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var relay: DirectTcpRelay? = null
    private var nativeSession: NativeTunSession? = null
    private var dnsResponder: LoopbackDnsProbeServer? = null

    @Volatile
    private var probeSocket: DatagramSocket? = null

    @Volatile
    private var generation: Long = 0L

    private var probeThread: Thread? = null
    private var nativeVersion: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TunnelContract.ACTION_STOP -> stopProbeAndService()
            TunnelContract.ACTION_START, null -> startProbe()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopProbeAndService()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startProbe() {
        promoteToForeground()
        if (hasActiveResources()) {
            closeResources()
        }

        try {
            val responder = LoopbackDnsProbeServer()
            val responderPort = responder.start()
            check(responderPort in 1..65535) {
                "Loopback DNS responder не открыл порт"
            }
            dnsResponder = responder

            val credentials = Socks5Credentials.random()
            val localRelay = DirectTcpRelay(
                socketProtector = SocketProtector { false },
                credentials = credentials,
                targetResolver = RelayTargetResolver { _, _ ->
                    throw IllegalArgumentException("TCP is disabled in DNS probe mode")
                },
                datagramSocketProtector = DatagramSocketProtector { socket ->
                    protect(socket)
                },
                udpTargetResolver = ExactUdpRelayTargetOverride(
                    source = RelayTarget(DNS_TEST_HOST, DNS_TEST_PORT),
                    destination = RelayTarget(RELAY_HOST, responderPort),
                ),
            )
            val relayPort = localRelay.start()
            check(relayPort in 1..65535) {
                "Локальный SOCKS5 relay не открыл порт"
            }
            relay = localRelay

            val tunnel = establishTestTunnel()
            tunnelDescriptor = tunnel

            check(NativeTunBridge.isAvailable()) {
                NativeTunBridge.loadError()
                    ?: "Native bridge недоступен для ABI этого устройства"
            }
            val version = NativeTunBridge.version().getOrElse { error ->
                throw IllegalStateException("JNI version self-check завершился ошибкой", error)
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
                nativeSession = session
            } catch (error: Throwable) {
                runCatching { session.close() }
                throw error
            }
            nativeVersion = version

            publishStatus(
                status = TunnelContract.STATUS_STARTED,
                nativeVersion = version,
            )
            launchProbe()
        } catch (error: Throwable) {
            val cleanupError = closeResources()
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                error = buildFailureMessage(error, cleanupError),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun establishTestTunnel(): ParcelFileDescriptor = Builder()
        .setSession("ConnectX v0.2 alpha DNS diagnostics")
        .setMtu(DEFAULT_MTU)
        .addAddress(LOCAL_TUN_ADDRESS, LOCAL_TUN_PREFIX)
        .addRoute(TEST_ROUTE, TEST_ROUTE_PREFIX)
        .setBlocking(false)
        .establish()
        ?: error("Android не создал локальный TUN-интерфейс")

    private fun launchProbe() {
        val probeGeneration = generation + 1L
        generation = probeGeneration
        val thread = Thread(
            {
                val outcome = runCatching { executeProbe() }
                mainHandler.post {
                    completeProbe(probeGeneration, outcome)
                }
            },
            "connectx-native-dns-probe",
        ).apply { isDaemon = true }
        probeThread = thread
        thread.start()
    }

    private fun executeProbe(): DnsProbeResult {
        check(NativeTunBridge.isRunning()) {
            "Native bridge остановился до DNS probe"
        }
        val transactionId = secureRandom.nextInt(0x1_0000)
        val query = DnsProbeProtocol.buildQuery(transactionId)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        Thread.sleep(ROUTE_SETTLE_MILLIS)
        var lastTimeout: SocketTimeoutException? = null

        repeat(PROBE_ATTEMPTS) { attempt ->
            check(NativeTunBridge.isRunning()) {
                "Native bridge остановился во время DNS probe"
            }
            val socket = DatagramSocket(null)
            probeSocket = socket
            try {
                socket.reuseAddress = true
                socket.bind(
                    InetSocketAddress(
                        InetAddress.getByName(IPV4_ANY_HOST),
                        0,
                    ),
                )
                socket.soTimeout = ATTEMPT_TIMEOUT_MILLIS
                // Intentionally not protected: this exact TEST-NET datagram must
                // enter Android TUN and the userspace native stack.
                socket.connect(InetSocketAddress(DNS_TEST_HOST, DNS_TEST_PORT))
                socket.send(DatagramPacket(query, query.size))

                val responseBuffer = ByteArray(DnsProbeProtocol.MAX_PACKET_BYTES + 1)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                val parsed = DnsProbeProtocol.parseResponse(
                    packet = response.data,
                    expectedTransactionId = transactionId,
                    length = response.length,
                )

                val relayStats = awaitRelayStats(
                    uploadedBytes = query.size.toLong(),
                    downloadedBytes = response.length.toLong(),
                )
                val responderStats = checkNotNull(dnsResponder) {
                    "DNS responder отсутствует во время probe"
                }.stats()

                check(relayStats.udpAssociations >= 1L) {
                    "Relay не подтвердил authenticated DNS UDP association"
                }
                check(relayStats.udpDatagrams >= 1L) {
                    "Relay не подтвердил DNS datagram exchange"
                }
                check(responderStats.queries >= 1L) {
                    "DNS responder не подтвердил запрос"
                }
                check(responderStats.responses >= 1L) {
                    "DNS responder не подтвердил ответ"
                }
                check(responderStats.rejected == 0L) {
                    "DNS responder отклонил пакет во время bounded probe"
                }

                return DnsProbeResult(
                    latencyMillis = elapsedMillisSince(startedAt),
                    uploadedBytes = relayStats.udpUploadedBytes,
                    downloadedBytes = relayStats.udpDownloadedBytes,
                    relayAssociations = relayStats.udpAssociations,
                    datagrams = relayStats.udpDatagrams,
                    queries = responderStats.queries,
                    responses = responderStats.responses,
                    answer = parsed.address.hostAddress.orEmpty(),
                )
            } catch (error: SocketTimeoutException) {
                lastTimeout = error
            } finally {
                if (probeSocket === socket) probeSocket = null
                runCatching { socket.close() }
            }

            if (attempt + 1 < PROBE_ATTEMPTS) {
                Thread.sleep(RETRY_DELAY_MILLIS)
            }
        }

        throw IOException(
            "DNS probe не получил ответ после $PROBE_ATTEMPTS IPv4 попыток; " +
                NativeTunBridge.transportDiagnostics(),
            lastTimeout,
        )
    }

    private fun awaitRelayStats(
        uploadedBytes: Long,
        downloadedBytes: Long,
    ): RelayStats {
        val localRelay = checkNotNull(relay) { "SOCKS5 relay отсутствует во время DNS probe" }
        val deadline = SystemClock.elapsedRealtime() + STATS_TIMEOUT_MILLIS
        var stats = localRelay.stats()
        while (
            (
                stats.udpAssociations < 1L ||
                    stats.udpDatagrams < 1L ||
                    stats.udpUploadedBytes < uploadedBytes ||
                    stats.udpDownloadedBytes < downloadedBytes
                ) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Thread.sleep(STATS_POLL_MILLIS)
            stats = localRelay.stats()
        }
        return stats
    }

    private fun completeProbe(
        probeGeneration: Long,
        outcome: Result<DnsProbeResult>,
    ) {
        if (probeGeneration != generation) return

        val completedVersion = nativeVersion
        val cleanupError = closeResources()
        val result = outcome.getOrNull()
        if (result != null && cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_DNS_PROBE_SUCCEEDED,
                nativeVersion = completedVersion,
                result = result,
            )
        } else {
            val primary = outcome.exceptionOrNull()
                ?: IllegalStateException("DNS probe завершён, но ресурсы закрылись с ошибкой")
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                nativeVersion = completedVersion,
                error = buildFailureMessage(primary, cleanupError),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProbeAndService() {
        val cleanupError = closeResources()
        if (cleanupError == null) {
            publishStatus(status = TunnelContract.STATUS_STOPPED)
        } else {
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                error = "Ресурсы остановлены с ошибкой: " +
                    (cleanupError.message ?: cleanupError::class.java.simpleName),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Closes client socket, native stack, TUN, relay, then DNS responder. */
    private fun closeResources(): Throwable? {
        var firstError: Throwable? = null
        generation += 1L

        val socket = probeSocket
        probeSocket = null
        runCatching { socket?.close() }
            .onFailure { error -> firstError = error }
        probeThread = null

        val session = nativeSession
        nativeSession = null
        runCatching { session?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        val descriptor = tunnelDescriptor
        tunnelDescriptor = null
        runCatching { descriptor?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        val localRelay = relay
        relay = null
        runCatching { localRelay?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        val responder = dnsResponder
        dnsResponder = null
        runCatching { responder?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        nativeVersion = null
        return firstError
    }

    private fun hasActiveResources(): Boolean =
        tunnelDescriptor != null ||
            relay != null ||
            nativeSession != null ||
            dnsResponder != null ||
            probeSocket != null

    private fun publishStatus(
        status: String,
        error: String? = null,
        nativeVersion: String? = null,
        result: DnsProbeResult? = null,
    ) {
        val intent = Intent(TunnelContract.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(TunnelContract.EXTRA_STATUS, status)
            putExtra(TunnelContract.EXTRA_ENGINE_MODE, TunnelContract.MODE_NATIVE_DNS_PROBE)
            putExtra(TunnelContract.EXTRA_NATIVE_ABI, currentAbi())
            nativeVersion?.let { putExtra(TunnelContract.EXTRA_NATIVE_VERSION, it) }
            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }
            result?.let { probe ->
                putExtra(TunnelContract.EXTRA_PROBE_LATENCY_MILLIS, probe.latencyMillis)
                putExtra(TunnelContract.EXTRA_PROBE_UPLOADED_BYTES, probe.uploadedBytes)
                putExtra(TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES, probe.downloadedBytes)
                putExtra(TunnelContract.EXTRA_PROBE_RELAY_ASSOCIATIONS, probe.relayAssociations)
                putExtra(TunnelContract.EXTRA_PROBE_DATAGRAMS, probe.datagrams)
                putExtra(TunnelContract.EXTRA_PROBE_DNS_QUERIES, probe.queries)
                putExtra(TunnelContract.EXTRA_PROBE_DNS_RESPONSES, probe.responses)
                putExtra(TunnelContract.EXTRA_PROBE_DNS_ANSWER, probe.answer)
            }
        }
        sendBroadcast(intent)
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
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

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ConnectXDnsProbeService::class.java).apply {
            action = TunnelContract.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("ConnectX")
            .setContentText("Проверка DNS через TEST-NET TUN")
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
            "DNS-диагностика ConnectX",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ограниченная локальная проверка DNS-пути через TUN"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long = max(
        1L,
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLISECOND,
    )

    private fun buildFailureMessage(primary: Throwable, cleanup: Throwable?): String {
        val primaryMessage = primary.message ?: primary::class.java.simpleName
        return if (cleanup == null) {
            primaryMessage
        } else {
            "$primaryMessage; ошибка очистки: ${cleanup.message ?: cleanup::class.java.simpleName}"
        }
    }

    private fun currentAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI

    private data class DnsProbeResult(
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayAssociations: Long,
        val datagrams: Long,
        val queries: Long,
        val responses: Long,
        val answer: String,
    )

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "connectx_dns_probe"
        const val NOTIFICATION_ID = 1003
        const val STOP_REQUEST_CODE = 1004

        const val DEFAULT_MTU = 1500
        const val LOCAL_TUN_ADDRESS = "10.222.0.2"
        const val LOCAL_TUN_PREFIX = 32
        const val TEST_ROUTE = "192.0.2.0"
        const val TEST_ROUTE_PREFIX = 24
        const val RELAY_HOST = "127.0.0.1"
        const val IPV4_ANY_HOST = "0.0.0.0"
        const val DNS_TEST_HOST = "192.0.2.53"
        const val DNS_TEST_PORT = 53

        const val ROUTE_SETTLE_MILLIS = 300L
        const val PROBE_ATTEMPTS = 3
        const val ATTEMPT_TIMEOUT_MILLIS = 1_500
        const val RETRY_DELAY_MILLIS = 150L
        const val STATS_TIMEOUT_MILLIS = 1_000L
        const val STATS_POLL_MILLIS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
