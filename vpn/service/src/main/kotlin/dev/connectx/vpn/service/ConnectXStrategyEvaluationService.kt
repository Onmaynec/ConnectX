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
import dev.connectx.strategy.api.NetworkProtocol
import dev.connectx.strategy.api.StrategyContext
import dev.connectx.strategy.api.StrategyEvaluationPolicy
import dev.connectx.strategy.api.StrategyEvaluationReport
import dev.connectx.strategy.api.StrategyFeatureGate
import dev.connectx.strategy.api.StrategyHealthEvaluator
import dev.connectx.strategy.api.StrategyHealthSample
import dev.connectx.strategy.api.StrategyPlan
import dev.connectx.strategy.api.StrategySampleFailure
import dev.connectx.strategy.api.StrategyScope
import dev.connectx.strategy.api.StrategySessionGate
import dev.connectx.strategy.api.StrategySessionGateState
import dev.connectx.strategy.api.TlsClientHelloSplitStrategy
import dev.connectx.strategy.api.TransportProtocol
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.nativebridge.NativeTunSession
import dev.connectx.vpn.relay.DirectTcpRelay
import dev.connectx.vpn.relay.ExactRelayTargetOverride
import dev.connectx.vpn.relay.LoopbackTcpEchoServer
import dev.connectx.vpn.relay.RelayStats
import dev.connectx.vpn.relay.RelayTarget
import dev.connectx.vpn.relay.SocketProtector
import dev.connectx.vpn.relay.Socks5Credentials
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.math.max

/**
 * Bounded A/B/A strategy evaluation over the TEST-NET-only local TUN path.
 *
 * Baseline and recovery use one write. The strategy phase uses the exact
 * ordered segments planned by [TlsClientHelloSplitStrategy]. No ordinary
 * application traffic, external host or decrypted TLS content is involved.
 */
class ConnectXStrategyEvaluationService : VpnService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val secureRandom = SecureRandom()

    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var relay: DirectTcpRelay? = null
    private var nativeSession: NativeTunSession? = null
    private var echoServer: LoopbackTcpEchoServer? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var generation: Long = 0L

    private var evaluationThread: Thread? = null
    private var nativeVersion: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TunnelContract.ACTION_STOP -> stopEvaluationAndService()
            TunnelContract.ACTION_START, null -> startEvaluation()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopEvaluationAndService()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startEvaluation() {
        promoteToForeground()
        if (hasActiveResources()) {
            closeResources()
        }

        try {
            val startedAt = SystemClock.elapsedRealtime()
            synchronized(GATE_LOCK) {
                processGate = processGate.begin(startedAt)
            }

            val localEchoServer = LoopbackTcpEchoServer(
                maxPayloadBytes = MAX_PAYLOAD_BYTES,
            )
            val echoPort = localEchoServer.start()
            check(echoPort in 1..65535) {
                "Loopback strategy endpoint не открыл порт"
            }
            echoServer = localEchoServer

            val credentials = Socks5Credentials.random()
            val localRelay = DirectTcpRelay(
                socketProtector = SocketProtector { socket -> protect(socket) },
                credentials = credentials,
                targetResolver = ExactRelayTargetOverride(
                    source = RelayTarget(EVALUATION_TEST_HOST, EVALUATION_TEST_PORT),
                    destination = RelayTarget(RELAY_HOST, echoPort),
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
            launchEvaluation()
        } catch (error: Throwable) {
            enterCooldownAfterFailure()
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
        .setSession("ConnectX v0.3 alpha strategy evaluation")
        .setMtu(DEFAULT_MTU)
        .addAddress(LOCAL_TUN_ADDRESS, LOCAL_TUN_PREFIX)
        .addRoute(TEST_ROUTE, TEST_ROUTE_PREFIX)
        .setBlocking(false)
        .establish()
        ?: error("Android не создал локальный TUN-интерфейс")

    private fun launchEvaluation() {
        val evaluationGeneration = generation + 1L
        generation = evaluationGeneration
        val thread = Thread(
            {
                val outcome = runCatching {
                    executeEvaluation(evaluationGeneration)
                }
                mainHandler.post {
                    completeEvaluation(evaluationGeneration, outcome)
                }
            },
            "connectx-strategy-evaluation",
        ).apply { isDaemon = true }
        evaluationThread = thread
        thread.start()
    }

    private fun executeEvaluation(
        expectedGeneration: Long,
    ): StrategyEvaluationResult {
        checkEvaluationActive(expectedGeneration)

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
            "Strategy planner отказался от валидного lab ClientHello: $plan"
        }
        check(payload.contentEquals(plan.reconstruct())) {
            "Strategy planner изменил reconstructed ClientHello"
        }

        Thread.sleep(ROUTE_SETTLE_MILLIS)
        checkEvaluationActive(expectedGeneration)

        val baseline = runPhase(
            expectedGeneration = expectedGeneration,
            payload = payload,
            segments = listOf(payload),
            writeGapMillis = 0L,
        )

        val strategyPhase = if (baseline.sample is StrategyHealthSample.Success) {
            runPhase(
                expectedGeneration = expectedGeneration,
                payload = payload,
                segments = plan.segments,
                writeGapMillis = STRATEGY_WRITE_GAP_MILLIS,
            )
        } else {
            PhaseOutcome(
                sample = StrategyHealthSample.Failure(
                    StrategySampleFailure.CANCELLED,
                ),
            )
        }

        // Recovery always uses the unmodified single-write path after a
        // strategy attempt. It is skipped only when baseline never worked.
        val recovery = if (baseline.sample is StrategyHealthSample.Success) {
            runPhase(
                expectedGeneration = expectedGeneration,
                payload = payload,
                segments = listOf(payload),
                writeGapMillis = 0L,
            )
        } else {
            PhaseOutcome(
                sample = StrategyHealthSample.Failure(
                    StrategySampleFailure.CANCELLED,
                ),
            )
        }

        checkEvaluationActive(expectedGeneration)
        val report = StrategyHealthEvaluator(EVALUATION_POLICY).evaluate(
            strategyId = strategy.descriptor.id,
            baselineSamples = listOf(baseline.sample),
            strategySamples = listOf(strategyPhase.sample),
            recoverySamples = listOf(recovery.sample),
        )
        val gate = synchronized(GATE_LOCK) {
            processGate = processGate.complete(
                report = report,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
                policy = EVALUATION_POLICY,
            )
            processGate
        }

        return StrategyEvaluationResult(
            report = report,
            gate = gate,
            segments = plan.segments.size,
            splitOffset = plan.splitOffset,
            baseline = baseline,
            strategy = strategyPhase,
            recovery = recovery,
        )
    }

    private fun runPhase(
        expectedGeneration: Long,
        payload: ByteArray,
        segments: List<ByteArray>,
        writeGapMillis: Long,
    ): PhaseOutcome {
        checkEvaluationActive(expectedGeneration)
        return try {
            val exchange = executeExchange(
                expectedGeneration = expectedGeneration,
                payload = payload,
                segments = segments,
                writeGapMillis = writeGapMillis,
            )
            PhaseOutcome(
                sample = StrategyHealthSample.Success(exchange.latencyMillis),
                latencyMillis = exchange.latencyMillis,
                uploadedBytes = exchange.uploadedBytes,
                downloadedBytes = exchange.downloadedBytes,
                relayConnections = exchange.relayConnections,
            )
        } catch (error: Throwable) {
            PhaseOutcome(
                sample = StrategyHealthSample.Failure(classifyFailure(error)),
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun executeExchange(
        expectedGeneration: Long,
        payload: ByteArray,
        segments: List<ByteArray>,
        writeGapMillis: Long,
    ): ExchangeResult {
        require(segments.isNotEmpty()) { "Exchange requires at least one segment" }
        require(segments.none { it.isEmpty() }) {
            "Exchange segments must not be empty"
        }
        val reconstructed = ByteArray(segments.sumOf(ByteArray::size))
        var reconstructedOffset = 0
        segments.forEach { segment ->
            segment.copyInto(reconstructed, destinationOffset = reconstructedOffset)
            reconstructedOffset += segment.size
        }
        check(payload.contentEquals(reconstructed)) {
            "Exchange segments changed the synthetic ClientHello"
        }
        checkEvaluationActive(expectedGeneration)

        val before = checkNotNull(relay) {
            "SOCKS5 relay отсутствует во время strategy evaluation"
        }.stats()
        val socket = Socket()
        activeSocket = socket
        val startedAt = SystemClock.elapsedRealtimeNanos()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = PHASE_TIMEOUT_MILLIS
            // Intentionally unprotected: the exact TEST-NET target must enter
            // Android TUN and traverse the userspace stack.
            socket.connect(
                InetSocketAddress(EVALUATION_TEST_HOST, EVALUATION_TEST_PORT),
                PHASE_TIMEOUT_MILLIS,
            )
            val output = socket.getOutputStream()
            segments.forEachIndexed { index, segment ->
                checkEvaluationActive(expectedGeneration)
                output.write(segment)
                output.flush()
                if (writeGapMillis > 0L && index + 1 < segments.size) {
                    Thread.sleep(writeGapMillis)
                }
            }

            val echoed = ByteArray(payload.size)
            DataInputStream(socket.getInputStream()).readFully(echoed)
            checkEvaluationActive(expectedGeneration)
            if (!payload.contentEquals(echoed)) {
                throw PayloadMismatchException(
                    "Strategy evaluation получила изменённый echo payload",
                )
            }

            val after = awaitRelayStats(
                expectedGeneration = expectedGeneration,
                before = before,
                payloadBytes = payload.size.toLong(),
            )
            return ExchangeResult(
                latencyMillis = elapsedMillisSince(startedAt),
                uploadedBytes = after.uploadedBytes - before.uploadedBytes,
                downloadedBytes = after.downloadedBytes - before.downloadedBytes,
                relayConnections = after.acceptedConnections - before.acceptedConnections,
            )
        } finally {
            if (activeSocket === socket) activeSocket = null
            runCatching { socket.close() }
        }
    }

    private fun awaitRelayStats(
        expectedGeneration: Long,
        before: RelayStats,
        payloadBytes: Long,
    ): RelayStats {
        val localRelay = checkNotNull(relay) {
            "SOCKS5 relay отсутствует во время strategy evaluation"
        }
        val deadline = SystemClock.elapsedRealtime() + STATS_TIMEOUT_MILLIS
        var stats = localRelay.stats()
        while (
            (
                stats.acceptedConnections < before.acceptedConnections + 1L ||
                    stats.uploadedBytes < before.uploadedBytes + payloadBytes ||
                    stats.downloadedBytes < before.downloadedBytes + payloadBytes
                ) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            checkEvaluationActive(expectedGeneration)
            Thread.sleep(STATS_POLL_MILLIS)
            stats = localRelay.stats()
        }
        checkEvaluationActive(expectedGeneration)
        check(stats.acceptedConnections >= before.acceptedConnections + 1L) {
            "Relay не подтвердил отдельное strategy evaluation соединение"
        }
        check(stats.uploadedBytes >= before.uploadedBytes + payloadBytes) {
            "Relay не подтвердил отправленные strategy evaluation bytes"
        }
        check(stats.downloadedBytes >= before.downloadedBytes + payloadBytes) {
            "Relay не подтвердил полученные strategy evaluation bytes"
        }
        return stats
    }

    private fun checkEvaluationActive(expectedGeneration: Long) {
        check(expectedGeneration == generation) {
            "Strategy evaluation была отменена"
        }
        check(NativeTunBridge.isRunning()) {
            "Native bridge остановился во время strategy evaluation"
        }
    }

    private fun classifyFailure(error: Throwable): StrategySampleFailure = when (error) {
        is PayloadMismatchException -> StrategySampleFailure.PAYLOAD_MISMATCH
        is SocketTimeoutException -> StrategySampleFailure.TIMEOUT
        is IOException -> StrategySampleFailure.CONNECTION_FAILED
        is IllegalArgumentException -> StrategySampleFailure.STRATEGY_REFUSED
        is InterruptedException -> StrategySampleFailure.CANCELLED
        is IllegalStateException -> {
            if (error.message?.contains("отменена") == true) {
                StrategySampleFailure.CANCELLED
            } else {
                StrategySampleFailure.INTERNAL_ERROR
            }
        }
        else -> StrategySampleFailure.INTERNAL_ERROR
    }

    private fun completeEvaluation(
        evaluationGeneration: Long,
        outcome: Result<StrategyEvaluationResult>,
    ) {
        if (evaluationGeneration != generation) return

        val completedVersion = nativeVersion
        val cleanupError = closeResources()
        val result = outcome.getOrNull()
        if (result != null && cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_STRATEGY_EVALUATION_COMPLETED,
                nativeVersion = completedVersion,
                result = result,
            )
        } else {
            enterCooldownAfterFailure()
            val primary = outcome.exceptionOrNull()
                ?: IllegalStateException(
                    "Strategy evaluation завершена, но ресурсы закрылись с ошибкой",
                )
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                nativeVersion = completedVersion,
                error = buildFailureMessage(primary, cleanupError),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopEvaluationAndService() {
        if (shouldEnterCooldownOnStop()) {
            enterCooldownAfterFailure()
        }
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

    /**
     * A late or duplicated STOP for an already completed service is idempotent.
     * Only a live resource set or an evaluation that has entered its gate may
     * produce cooldown; an approved result must not be poisoned after teardown.
     */
    private fun shouldEnterCooldownOnStop(): Boolean =
        hasActiveResources() || synchronized(GATE_LOCK) {
            processGate.state == StrategySessionGateState.EVALUATING
        }

    private fun enterCooldownAfterFailure() {
        val now = SystemClock.elapsedRealtime()
        synchronized(GATE_LOCK) {
            processGate = when (processGate.state) {
                StrategySessionGateState.EVALUATING ->
                    processGate.abort(now, EVALUATION_POLICY)
                StrategySessionGateState.LAB_APPROVED ->
                    processGate
                        .resetApprovedSession()
                        .begin(now)
                        .abort(now, EVALUATION_POLICY)
                StrategySessionGateState.READY ->
                    processGate
                        .begin(now)
                        .abort(now, EVALUATION_POLICY)
                StrategySessionGateState.COOLDOWN,
                StrategySessionGateState.DISABLED,
                -> processGate
            }
        }
    }

    private fun closeResources(): Throwable? {
        var firstError: Throwable? = null
        generation += 1L

        val socket = activeSocket
        activeSocket = null
        runCatching { socket?.close() }
            .onFailure { error -> firstError = error }
        evaluationThread = null

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

        val endpoint = echoServer
        echoServer = null
        runCatching { endpoint?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        nativeVersion = null
        return firstError
    }

    private fun hasActiveResources(): Boolean =
        tunnelDescriptor != null ||
            relay != null ||
            nativeSession != null ||
            echoServer != null ||
            activeSocket != null

    private fun buildSyntheticClientHello(): ByteArray {
        val body = ByteArray(TLS_CLIENT_HELLO_BODY_BYTES)
        secureRandom.nextBytes(body)
        body[0] = 0x03
        body[1] = 0x03
        body[body.lastIndex] = 0

        val handshake = ByteArray(TLS_HANDSHAKE_HEADER_BYTES + body.size)
        handshake[0] = 0x01
        handshake[1] = ((body.size ushr 16) and 0xff).toByte()
        handshake[2] = ((body.size ushr 8) and 0xff).toByte()
        handshake[3] = (body.size and 0xff).toByte()
        body.copyInto(handshake, destinationOffset = TLS_HANDSHAKE_HEADER_BYTES)

        return ByteArray(TLS_RECORD_HEADER_BYTES + handshake.size).also { record ->
            record[0] = 0x16
            record[1] = 0x03
            record[2] = 0x03
            record[3] = ((handshake.size ushr 8) and 0xff).toByte()
            record[4] = (handshake.size and 0xff).toByte()
            handshake.copyInto(record, destinationOffset = TLS_RECORD_HEADER_BYTES)
        }
    }

    private fun publishStatus(
        status: String,
        error: String? = null,
        nativeVersion: String? = null,
        result: StrategyEvaluationResult? = null,
    ) {
        val intent = Intent(TunnelContract.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(TunnelContract.EXTRA_STATUS, status)
            putExtra(
                TunnelContract.EXTRA_ENGINE_MODE,
                TunnelContract.MODE_NATIVE_STRATEGY_EVALUATION,
            )
            putExtra(TunnelContract.EXTRA_NATIVE_ABI, currentAbi())
            nativeVersion?.let { putExtra(TunnelContract.EXTRA_NATIVE_VERSION, it) }
            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }
            result?.let { evaluation ->
                val report = evaluation.report
                putExtra(TunnelContract.EXTRA_STRATEGY_ID, report.strategyId.value)
                putExtra(TunnelContract.EXTRA_STRATEGY_SEGMENTS, evaluation.segments)
                putExtra(TunnelContract.EXTRA_STRATEGY_SPLIT_OFFSET, evaluation.splitOffset)
                putExtra(TunnelContract.EXTRA_STRATEGY_DECISION, report.decision.name)
                putExtra(TunnelContract.EXTRA_STRATEGY_REASON, report.reason.name)
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_BASELINE_LATENCY_MILLIS,
                    evaluation.baseline.latencyMillis ?: -1L,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_LATENCY_MILLIS,
                    evaluation.strategy.latencyMillis ?: -1L,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_RECOVERY_LATENCY_MILLIS,
                    evaluation.recovery.latencyMillis ?: -1L,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_LATENCY_DELTA_MILLIS,
                    report.latencyDeltaMillis ?: Long.MIN_VALUE,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_ALLOWED_LATENCY_MILLIS,
                    report.allowedStrategyLatencyMillis ?: -1L,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_BASELINE_FAILURE,
                    evaluation.baseline.failureName,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_PHASE_FAILURE,
                    evaluation.strategy.failureName,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_RECOVERY_FAILURE,
                    evaluation.recovery.failureName,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_UPLOADED_BYTES,
                    evaluation.totalUploadedBytes,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES,
                    evaluation.totalDownloadedBytes,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS,
                    evaluation.totalRelayConnections,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_GATE_STATE,
                    evaluation.gate.state.name,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_COOLDOWN_UNTIL_MILLIS,
                    evaluation.gate.cooldownUntilElapsedMillis ?: -1L,
                )
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
        val stopIntent = Intent(this, ConnectXStrategyEvaluationService::class.java).apply {
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
            .setContentText("A/B/A оценка TLS strategy · только TEST-NET Lab")
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
            "Strategy Lab ConnectX",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ограниченная A/B/A оценка strategy через TEST-NET TUN"
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

    private data class ExchangeResult(
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayConnections: Long,
    )

    private data class PhaseOutcome(
        val sample: StrategyHealthSample,
        val latencyMillis: Long? = null,
        val uploadedBytes: Long = 0L,
        val downloadedBytes: Long = 0L,
        val relayConnections: Long = 0L,
        val error: String? = null,
    ) {
        val failureName: String?
            get() = (sample as? StrategyHealthSample.Failure)?.reason?.name
    }

    private data class StrategyEvaluationResult(
        val report: StrategyEvaluationReport,
        val gate: StrategySessionGate,
        val segments: Int,
        val splitOffset: Int,
        val baseline: PhaseOutcome,
        val strategy: PhaseOutcome,
        val recovery: PhaseOutcome,
    ) {
        val totalUploadedBytes: Long
            get() = baseline.uploadedBytes + strategy.uploadedBytes + recovery.uploadedBytes
        val totalDownloadedBytes: Long
            get() = baseline.downloadedBytes + strategy.downloadedBytes + recovery.downloadedBytes
        val totalRelayConnections: Long
            get() = baseline.relayConnections + strategy.relayConnections + recovery.relayConnections
    }

    private class PayloadMismatchException(message: String) : IOException(message)

    private companion object {
        val GATE_LOCK = Any()

        @Volatile
        var processGate = StrategySessionGate()

        val EVALUATION_POLICY = StrategyEvaluationPolicy(
            requiredSuccessesPerPhase = 1,
            maxFailuresPerPhase = 0,
            maxLatencyRegressionPercent = 50,
            maxAbsoluteLatencyRegressionMillis = 100L,
            cooldownMillis = 60_000L,
        )

        const val NOTIFICATION_CHANNEL_ID = "connectx_strategy_evaluation"
        const val NOTIFICATION_ID = 1005
        const val STOP_REQUEST_CODE = 1006
        const val DEFAULT_MTU = 1500
        const val LOCAL_TUN_ADDRESS = "10.222.0.2"
        const val LOCAL_TUN_PREFIX = 32
        const val TEST_ROUTE = "192.0.2.0"
        const val TEST_ROUTE_PREFIX = 24
        const val RELAY_HOST = "127.0.0.1"
        const val EVALUATION_TEST_HOST = "192.0.2.1"
        const val EVALUATION_TEST_PORT = 18_444
        const val MAX_PAYLOAD_BYTES = 4 * 1024
        const val PHASE_TIMEOUT_MILLIS = 5_000
        const val ROUTE_SETTLE_MILLIS = 300L
        const val STRATEGY_WRITE_GAP_MILLIS = 25L
        const val STATS_TIMEOUT_MILLIS = 1_000L
        const val STATS_POLL_MILLIS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val TLS_RECORD_HEADER_BYTES = 5
        const val TLS_HANDSHAKE_HEADER_BYTES = 4
        const val TLS_CLIENT_HELLO_BODY_BYTES = 35
    }
}
