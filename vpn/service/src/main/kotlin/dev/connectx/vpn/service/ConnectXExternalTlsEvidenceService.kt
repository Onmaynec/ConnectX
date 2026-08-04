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
import dev.connectx.strategy.api.ExternalTlsEvidenceTarget
import dev.connectx.strategy.api.HostnameValidationResult
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
import dev.connectx.strategy.api.TargetResolutionResult
import dev.connectx.strategy.api.TlsClientHelloCreationResult
import dev.connectx.strategy.api.TlsClientHelloFactory
import dev.connectx.strategy.api.TlsClientHelloSplitStrategy
import dev.connectx.strategy.api.TlsRecordKind
import dev.connectx.strategy.api.TlsRecordPrefixClassifier
import dev.connectx.strategy.api.TlsRecordPrefixResult
import dev.connectx.strategy.api.TransportProtocol
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.nativebridge.NativeTunSession
import dev.connectx.vpn.relay.DirectTcpRelay
import dev.connectx.vpn.relay.RelayStats
import dev.connectx.vpn.relay.RelayTarget
import dev.connectx.vpn.relay.RelayTargetResolver
import dev.connectx.vpn.relay.SocketProtector
import dev.connectx.vpn.relay.Socks5Credentials
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.max

/**
 * Manual one-host TLS evidence probe for a restricted network.
 *
 * Only an exact TEST-NET socket enters the local TUN. The authenticated relay
 * rewrites that one endpoint to one public IPv4 selected before TUN startup and
 * protects the outbound socket through [VpnService.protect]. The service sends
 * one locally generated ClientHello per phase and reads only a five-byte TLS
 * record header. It never sends an HTTP request or decrypts HTTPS content.
 */
class ConnectXExternalTlsEvidenceService : VpnService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var relay: DirectTcpRelay? = null
    private var nativeSession: NativeTunSession? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var generation: Long = 0L

    private var evidenceThread: Thread? = null
    private var nativeVersion: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TunnelContract.ACTION_STOP -> stopEvidenceAndService()
            TunnelContract.ACTION_START, null -> startEvidence(
                rawHostname = intent
                    ?.getStringExtra(TunnelContract.EXTRA_EVIDENCE_HOSTNAME)
                    .orEmpty(),
            )
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopEvidenceAndService()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startEvidence(rawHostname: String) {
        promoteToForeground()
        if (hasActiveResources()) {
            closeResources()
        }

        val evidenceGeneration = generation + 1L
        generation = evidenceGeneration
        val thread = Thread(
            {
                val outcome = runCatching {
                    executeEvidence(
                        expectedGeneration = evidenceGeneration,
                        rawHostname = rawHostname,
                    )
                }
                mainHandler.post {
                    completeEvidence(evidenceGeneration, outcome)
                }
            },
            "connectx-external-tls-evidence",
        ).apply { isDaemon = true }
        evidenceThread = thread
        thread.start()
    }

    private fun executeEvidence(
        expectedGeneration: Long,
        rawHostname: String,
    ): ExternalTlsEvidenceResult {
        checkGeneration(expectedGeneration)
        beginSessionGate()

        val normalizedHostname = when (
            val validation = ExternalTlsEvidenceTarget.validateHostname(rawHostname)
        ) {
            is HostnameValidationResult.Valid -> validation.normalizedHostname
            is HostnameValidationResult.Rejected -> throw EvidenceSetupException(
                "Hostname отклонён политикой безопасности: ${validation.reason.name}",
            )
        }

        checkGeneration(expectedGeneration)
        val resolvedAddresses = try {
            InetAddress.getAllByName(normalizedHostname).toList()
        } catch (_: UnknownHostException) {
            throw EvidenceSetupException("DNS не вернул адрес для указанного hostname")
        } catch (_: SecurityException) {
            throw EvidenceSetupException("Системная DNS-проверка запрещена")
        }
        val target = when (
            val resolution = ExternalTlsEvidenceTarget.bindResolvedAddresses(
                normalizedHostname = normalizedHostname,
                addresses = resolvedAddresses,
            )
        ) {
            is TargetResolutionResult.Valid -> resolution.target
            is TargetResolutionResult.Rejected -> throw EvidenceSetupException(
                "Resolved target отклонён политикой безопасности: ${resolution.reason.name}",
            )
        }

        val payload = when (
            val creation = TlsClientHelloFactory.create(normalizedHostname)
        ) {
            is TlsClientHelloCreationResult.Created -> creation.payload
            is TlsClientHelloCreationResult.Rejected -> throw EvidenceSetupException(
                "TLS ClientHello не создан: ${creation.reason.name}",
            )
        }
        val strategy = TlsClientHelloSplitStrategy()
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
            "Strategy planner отказался от локально созданного ClientHello: $plan"
        }
        check(payload.contentEquals(plan.reconstruct())) {
            "Strategy planner изменил reconstructed ClientHello"
        }

        checkGeneration(expectedGeneration)
        val relayPort = startExactRelay(target)
        val tunnel = establishTestTunnel()
        tunnelDescriptor = tunnel
        val version = startNativeSession(
            tunnel = tunnel,
            relayPort = relayPort,
        )
        nativeVersion = version

        publishStatus(
            status = TunnelContract.STATUS_STARTED,
            nativeVersion = version,
            target = target,
        )

        Thread.sleep(ROUTE_SETTLE_MILLIS)
        checkEvidenceActive(expectedGeneration)

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
            cancelledPhase()
        }
        val recovery = if (baseline.sample is StrategyHealthSample.Success) {
            runPhase(
                expectedGeneration = expectedGeneration,
                payload = payload,
                segments = listOf(payload),
                writeGapMillis = 0L,
            )
        } else {
            cancelledPhase()
        }

        checkEvidenceActive(expectedGeneration)
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

        return ExternalTlsEvidenceResult(
            target = target,
            report = report,
            gate = gate,
            segments = plan.segments.size,
            splitOffset = plan.splitOffset,
            baseline = baseline,
            strategy = strategyPhase,
            recovery = recovery,
        )
    }

    private fun beginSessionGate() {
        val now = SystemClock.elapsedRealtime()
        synchronized(GATE_LOCK) {
            processGate = processGate.begin(now)
        }
    }

    private fun startExactRelay(target: ExternalTlsEvidenceTarget): Int {
        val credentials = Socks5Credentials.random()
        val targetResolver = RelayTargetResolver { host, port ->
            check(host == EVIDENCE_TEST_HOST && port == EVIDENCE_TEST_PORT) {
                "SOCKS target находится вне exact evidence allow-list"
            }
            RelayTarget(
                host = target.ipv4Address,
                port = target.port,
            )
        }
        val localRelay = DirectTcpRelay(
            socketProtector = SocketProtector { socket -> protect(socket) },
            credentials = credentials,
            targetResolver = targetResolver,
        )
        val relayPort = localRelay.start()
        check(relayPort in 1..65535) {
            "Локальный SOCKS5 relay не открыл порт"
        }
        relay = localRelay
        relayCredentials = credentials
        return relayPort
    }

    private var relayCredentials: Socks5Credentials? = null

    private fun establishTestTunnel(): ParcelFileDescriptor = Builder()
        .setSession("ConnectX v0.3 external TLS evidence")
        .setMtu(DEFAULT_MTU)
        .addAddress(LOCAL_TUN_ADDRESS, LOCAL_TUN_PREFIX)
        .addRoute(TEST_ROUTE, TEST_ROUTE_PREFIX)
        .setBlocking(false)
        .establish()
        ?: error("Android не создал локальный TUN-интерфейс")

    private fun startNativeSession(
        tunnel: ParcelFileDescriptor,
        relayPort: Int,
    ): String {
        check(NativeTunBridge.isAvailable()) {
            NativeTunBridge.loadError()
                ?: "Native bridge недоступен для ABI этого устройства"
        }
        val version = NativeTunBridge.version().getOrElse { error ->
            throw IllegalStateException("JNI version self-check завершился ошибкой", error)
        }
        val credentials = checkNotNull(relayCredentials) {
            "Relay credentials отсутствуют во время запуска native bridge"
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
        return version
    }

    private fun runPhase(
        expectedGeneration: Long,
        payload: ByteArray,
        segments: List<ByteArray>,
        writeGapMillis: Long,
    ): EvidencePhaseOutcome {
        checkEvidenceActive(expectedGeneration)
        return try {
            val exchange = executeExchange(
                expectedGeneration = expectedGeneration,
                payload = payload,
                segments = segments,
                writeGapMillis = writeGapMillis,
            )
            EvidencePhaseOutcome(
                sample = StrategyHealthSample.Success(exchange.latencyMillis),
                latencyMillis = exchange.latencyMillis,
                uploadedBytes = exchange.uploadedBytes,
                downloadedBytes = exchange.downloadedBytes,
                relayConnections = exchange.relayConnections,
                recordKind = exchange.recordKind,
            )
        } catch (error: Throwable) {
            EvidencePhaseOutcome(
                sample = StrategyHealthSample.Failure(classifyFailure(error)),
                error = safePhaseError(error),
            )
        }
    }

    private fun executeExchange(
        expectedGeneration: Long,
        payload: ByteArray,
        segments: List<ByteArray>,
        writeGapMillis: Long,
    ): EvidenceExchangeResult {
        require(segments.isNotEmpty()) { "Evidence exchange requires at least one segment" }
        require(segments.none(ByteArray::isEmpty)) {
            "Evidence exchange segments must not be empty"
        }
        val reconstructed = ByteArray(segments.sumOf(ByteArray::size))
        var offset = 0
        segments.forEach { segment ->
            segment.copyInto(reconstructed, destinationOffset = offset)
            offset += segment.size
        }
        check(payload.contentEquals(reconstructed)) {
            "Evidence exchange segments changed ClientHello"
        }
        checkEvidenceActive(expectedGeneration)

        val localRelay = checkNotNull(relay) {
            "SOCKS5 relay отсутствует во время evidence phase"
        }
        val before = localRelay.stats()
        val socket = Socket()
        activeSocket = socket
        val startedAt = SystemClock.elapsedRealtimeNanos()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = PHASE_TIMEOUT_MILLIS
            // Intentionally unprotected: only this exact TEST-NET endpoint must
            // enter Android TUN. The relay's real outbound socket is protected.
            socket.connect(
                InetSocketAddress(EVIDENCE_TEST_HOST, EVIDENCE_TEST_PORT),
                PHASE_TIMEOUT_MILLIS,
            )
            val output = socket.getOutputStream()
            segments.forEachIndexed { index, segment ->
                checkEvidenceActive(expectedGeneration)
                output.write(segment)
                output.flush()
                if (writeGapMillis > 0L && index + 1 < segments.size) {
                    Thread.sleep(writeGapMillis)
                }
            }

            val header = ByteArray(TlsRecordPrefixClassifier.HEADER_BYTES)
            DataInputStream(socket.getInputStream()).readFully(header)
            checkEvidenceActive(expectedGeneration)
            val classification = TlsRecordPrefixClassifier.classify(header)
            val accepted = classification as? TlsRecordPrefixResult.Accepted
                ?: throw InvalidTlsPrefixException(
                    "Target вернул недопустимый TLS record header: " +
                        (classification as TlsRecordPrefixResult.Rejected).reason.name,
                )

            val after = awaitRelayStats(
                expectedGeneration = expectedGeneration,
                before = before,
                uploadedBytes = payload.size.toLong(),
                downloadedBytes = header.size.toLong(),
            )
            return EvidenceExchangeResult(
                latencyMillis = elapsedMillisSince(startedAt),
                uploadedBytes = after.uploadedBytes - before.uploadedBytes,
                downloadedBytes = after.downloadedBytes - before.downloadedBytes,
                relayConnections = after.acceptedConnections - before.acceptedConnections,
                recordKind = accepted.kind,
            )
        } finally {
            if (activeSocket === socket) activeSocket = null
            runCatching { socket.close() }
        }
    }

    private fun awaitRelayStats(
        expectedGeneration: Long,
        before: RelayStats,
        uploadedBytes: Long,
        downloadedBytes: Long,
    ): RelayStats {
        val localRelay = checkNotNull(relay) {
            "SOCKS5 relay отсутствует во время evidence phase"
        }
        val deadline = SystemClock.elapsedRealtime() + STATS_TIMEOUT_MILLIS
        var stats = localRelay.stats()
        while (
            (
                stats.acceptedConnections < before.acceptedConnections + 1L ||
                    stats.uploadedBytes < before.uploadedBytes + uploadedBytes ||
                    stats.downloadedBytes < before.downloadedBytes + downloadedBytes
                ) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            checkEvidenceActive(expectedGeneration)
            Thread.sleep(STATS_POLL_MILLIS)
            stats = localRelay.stats()
        }
        checkEvidenceActive(expectedGeneration)
        check(stats.acceptedConnections >= before.acceptedConnections + 1L) {
            "Relay не подтвердил отдельное evidence соединение"
        }
        check(stats.uploadedBytes >= before.uploadedBytes + uploadedBytes) {
            "Relay не подтвердил отправленный ClientHello"
        }
        check(stats.downloadedBytes >= before.downloadedBytes + downloadedBytes) {
            "Relay не подтвердил TLS record header"
        }
        return stats
    }

    private fun cancelledPhase(): EvidencePhaseOutcome = EvidencePhaseOutcome(
        sample = StrategyHealthSample.Failure(StrategySampleFailure.CANCELLED),
    )

    private fun checkGeneration(expectedGeneration: Long) {
        check(expectedGeneration == generation) {
            "External TLS evidence была отменена"
        }
    }

    private fun checkEvidenceActive(expectedGeneration: Long) {
        checkGeneration(expectedGeneration)
        check(NativeTunBridge.isRunning()) {
            "Native bridge остановился во время external TLS evidence"
        }
    }

    private fun classifyFailure(error: Throwable): StrategySampleFailure = when (error) {
        is InvalidTlsPrefixException -> StrategySampleFailure.PAYLOAD_MISMATCH
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

    private fun safePhaseError(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "TIMEOUT"
        is InvalidTlsPrefixException -> "INVALID_TLS_PREFIX"
        is IOException -> "CONNECTION_FAILED"
        is InterruptedException -> "CANCELLED"
        else -> error::class.java.simpleName
    }

    private fun completeEvidence(
        evidenceGeneration: Long,
        outcome: Result<ExternalTlsEvidenceResult>,
    ) {
        if (evidenceGeneration != generation) return

        val completedVersion = nativeVersion
        val cleanupError = closeResources()
        val result = outcome.getOrNull()
        if (result != null && cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_EXTERNAL_TLS_EVIDENCE_COMPLETED,
                nativeVersion = completedVersion,
                target = result.target,
                result = result,
            )
        } else {
            enterCooldownAfterFailure()
            val primary = outcome.exceptionOrNull()
                ?: IllegalStateException(
                    "External TLS evidence завершена, но ресурсы закрылись с ошибкой",
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

    private fun stopEvidenceAndService() {
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

        val thread = evidenceThread
        evidenceThread = null
        runCatching { thread?.interrupt() }
            .onFailure { error -> if (firstError == null) firstError = error }

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

        relayCredentials = null
        nativeVersion = null
        return firstError
    }

    private fun hasActiveResources(): Boolean =
        evidenceThread != null ||
            tunnelDescriptor != null ||
            relay != null ||
            nativeSession != null ||
            activeSocket != null

    private fun publishStatus(
        status: String,
        error: String? = null,
        nativeVersion: String? = null,
        target: ExternalTlsEvidenceTarget? = null,
        result: ExternalTlsEvidenceResult? = null,
    ) {
        val intent = Intent(TunnelContract.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(TunnelContract.EXTRA_STATUS, status)
            putExtra(
                TunnelContract.EXTRA_ENGINE_MODE,
                TunnelContract.MODE_NATIVE_EXTERNAL_TLS_EVIDENCE,
            )
            putExtra(TunnelContract.EXTRA_NATIVE_ABI, currentAbi())
            nativeVersion?.let { putExtra(TunnelContract.EXTRA_NATIVE_VERSION, it) }
            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }
            target?.let { evidenceTarget ->
                putExtra(TunnelContract.EXTRA_EVIDENCE_HOSTNAME, evidenceTarget.hostname)
                putExtra(
                    TunnelContract.EXTRA_EVIDENCE_RESOLVED_IPV4,
                    evidenceTarget.ipv4Address,
                )
                putExtra(TunnelContract.EXTRA_EVIDENCE_TARGET_PORT, evidenceTarget.port)
            }
            result?.let { evidence ->
                val report = evidence.report
                putExtra(TunnelContract.EXTRA_STRATEGY_ID, report.strategyId.value)
                putExtra(TunnelContract.EXTRA_STRATEGY_SEGMENTS, evidence.segments)
                putExtra(TunnelContract.EXTRA_STRATEGY_SPLIT_OFFSET, evidence.splitOffset)
                putExtra(TunnelContract.EXTRA_STRATEGY_DECISION, report.decision.name)
                putExtra(TunnelContract.EXTRA_STRATEGY_REASON, report.reason.name)
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_BASELINE_LATENCY_MILLIS,
                    evidence.baseline.latencyMillis ?: -1L,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_LATENCY_MILLIS,
                    evidence.strategy.latencyMillis ?: -1L,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_RECOVERY_LATENCY_MILLIS,
                    evidence.recovery.latencyMillis ?: -1L,
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
                    evidence.baseline.failureName,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_PHASE_FAILURE,
                    evidence.strategy.failureName,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_RECOVERY_FAILURE,
                    evidence.recovery.failureName,
                )
                putExtra(
                    TunnelContract.EXTRA_EVIDENCE_BASELINE_RECORD_KIND,
                    evidence.baseline.recordKind?.name,
                )
                putExtra(
                    TunnelContract.EXTRA_EVIDENCE_STRATEGY_RECORD_KIND,
                    evidence.strategy.recordKind?.name,
                )
                putExtra(
                    TunnelContract.EXTRA_EVIDENCE_RECOVERY_RECORD_KIND,
                    evidence.recovery.recordKind?.name,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_UPLOADED_BYTES,
                    evidence.totalUploadedBytes,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES,
                    evidence.totalDownloadedBytes,
                )
                putExtra(
                    TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS,
                    evidence.totalRelayConnections,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_GATE_STATE,
                    evidence.gate.state.name,
                )
                putExtra(
                    TunnelContract.EXTRA_STRATEGY_COOLDOWN_UNTIL_MILLIS,
                    evidence.gate.cooldownUntilElapsedMillis ?: -1L,
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
        val stopIntent = Intent(this, ConnectXExternalTlsEvidenceService::class.java).apply {
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
            .setContentText("Внешняя TLS-проверка · один host · TCP/443")
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
            "TLS Evidence Lab ConnectX",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ручная проверка одного публичного TLS hostname через TEST-NET TUN"
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

    private data class EvidenceExchangeResult(
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayConnections: Long,
        val recordKind: TlsRecordKind,
    )

    private data class EvidencePhaseOutcome(
        val sample: StrategyHealthSample,
        val latencyMillis: Long? = null,
        val uploadedBytes: Long = 0L,
        val downloadedBytes: Long = 0L,
        val relayConnections: Long = 0L,
        val recordKind: TlsRecordKind? = null,
        val error: String? = null,
    ) {
        val failureName: String?
            get() = (sample as? StrategyHealthSample.Failure)?.reason?.name
    }

    private data class ExternalTlsEvidenceResult(
        val target: ExternalTlsEvidenceTarget,
        val report: StrategyEvaluationReport,
        val gate: StrategySessionGate,
        val segments: Int,
        val splitOffset: Int,
        val baseline: EvidencePhaseOutcome,
        val strategy: EvidencePhaseOutcome,
        val recovery: EvidencePhaseOutcome,
    ) {
        val totalUploadedBytes: Long
            get() = baseline.uploadedBytes + strategy.uploadedBytes + recovery.uploadedBytes
        val totalDownloadedBytes: Long
            get() = baseline.downloadedBytes + strategy.downloadedBytes + recovery.downloadedBytes
        val totalRelayConnections: Long
            get() = baseline.relayConnections + strategy.relayConnections + recovery.relayConnections
    }

    private class EvidenceSetupException(message: String) : IOException(message)
    private class InvalidTlsPrefixException(message: String) : IOException(message)

    private companion object {
        val GATE_LOCK = Any()

        @Volatile
        var processGate = StrategySessionGate()

        val EVALUATION_POLICY = StrategyEvaluationPolicy(
            requiredSuccessesPerPhase = 1,
            maxFailuresPerPhase = 0,
            maxLatencyRegressionPercent = 50,
            maxAbsoluteLatencyRegressionMillis = 250L,
            cooldownMillis = 60_000L,
        )

        const val NOTIFICATION_CHANNEL_ID = "connectx_external_tls_evidence"
        const val NOTIFICATION_ID = 1007
        const val STOP_REQUEST_CODE = 1008
        const val DEFAULT_MTU = 1500
        const val LOCAL_TUN_ADDRESS = "10.222.0.2"
        const val LOCAL_TUN_PREFIX = 32
        const val TEST_ROUTE = "192.0.2.0"
        const val TEST_ROUTE_PREFIX = 24
        const val RELAY_HOST = "127.0.0.1"
        const val EVIDENCE_TEST_HOST = "192.0.2.1"
        const val EVIDENCE_TEST_PORT = 18_445
        const val PHASE_TIMEOUT_MILLIS = 6_000
        const val ROUTE_SETTLE_MILLIS = 300L
        const val STRATEGY_WRITE_GAP_MILLIS = 25L
        const val STATS_TIMEOUT_MILLIS = 1_500L
        const val STATS_POLL_MILLIS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
