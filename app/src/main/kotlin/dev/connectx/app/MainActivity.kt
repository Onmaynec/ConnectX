package dev.connectx.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.connectx.app.evidence.ExternalTlsEvidencePanel
import dev.connectx.app.evidence.ExternalTlsEvidenceStatus
import dev.connectx.app.evidence.ExternalTlsEvidenceUiState
import dev.connectx.app.home.HomeScreen
import dev.connectx.core.designsystem.ConnectXTheme
import dev.connectx.core.model.ConnectionEvent
import dev.connectx.core.model.ConnectionState
import dev.connectx.core.model.ConnectionStateReducer
import dev.connectx.core.model.ConnectionUiState
import dev.connectx.core.model.EngineMode
import dev.connectx.strategy.api.ExternalTlsEvidencePreset
import dev.connectx.strategy.api.ExternalTlsEvidenceTarget
import dev.connectx.strategy.api.HostnameValidationResult
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.service.ConnectXDnsProbeService
import dev.connectx.vpn.service.ConnectXExternalTlsEvidenceService
import dev.connectx.vpn.service.ConnectXStrategyEvaluationService
import dev.connectx.vpn.service.ConnectXTunnelService

class MainActivity : ComponentActivity() {
    private val connectionState = mutableStateOf(ConnectionUiState())
    private val externalEvidenceState = mutableStateOf(ExternalTlsEvidenceUiState())

    private var pendingMode: EngineMode = EngineMode.FOUNDATION
    private var pendingVpnRequest: PendingVpnRequest = PendingVpnRequest.NONE
    private var pendingEvidenceHostname: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (pendingVpnRequest) {
            PendingVpnRequest.EXTERNAL_TLS_EVIDENCE -> {
                if (result.resultCode == Activity.RESULT_OK) {
                    startExternalTlsEvidenceService()
                } else {
                    externalEvidenceState.value = externalEvidenceState.value.copy(
                        status = ExternalTlsEvidenceStatus.ERROR,
                        error = "Системное VPN-разрешение не предоставлено",
                    )
                    pendingEvidenceHostname = null
                }
            }

            PendingVpnRequest.TUNNEL -> {
                if (result.resultCode == Activity.RESULT_OK) {
                    dispatch(ConnectionEvent.PermissionGranted)
                    startTunnelService(pendingMode)
                } else {
                    dispatch(ConnectionEvent.PermissionDenied)
                    pendingMode = EngineMode.FOUNDATION
                }
            }

            PendingVpnRequest.NONE -> Unit
        }
        pendingVpnRequest = PendingVpnRequest.NONE
    }

    private val tunnelStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val statusIntent = intent ?: return
            if (
                statusIntent.getStringExtra(TunnelContract.EXTRA_ENGINE_MODE) ==
                TunnelContract.MODE_NATIVE_EXTERNAL_TLS_EVIDENCE
            ) {
                handleExternalEvidenceStatus(statusIntent)
                return
            }

            val mode = statusIntent.readEngineMode()
            when (statusIntent.getStringExtra(TunnelContract.EXTRA_STATUS)) {
                TunnelContract.STATUS_STARTED -> dispatch(
                    ConnectionEvent.TunnelStarted(
                        mode = mode,
                        nativeVersion = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_VERSION,
                        ),
                        abi = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_ABI,
                        ),
                    ),
                )

                TunnelContract.STATUS_PROBE_SUCCEEDED -> dispatch(
                    ConnectionEvent.ProbeCompleted(
                        latencyMillis = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_LATENCY_MILLIS,
                            0L,
                        ),
                        uploadedBytes = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_UPLOADED_BYTES,
                            0L,
                        ),
                        downloadedBytes = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES,
                            0L,
                        ),
                        relayConnections = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS,
                            0L,
                        ),
                        nativeVersion = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_VERSION,
                        ),
                        abi = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_ABI,
                        ),
                    ),
                )

                TunnelContract.STATUS_UDP_PROBE_SUCCEEDED -> dispatch(
                    ConnectionEvent.UdpProbeCompleted(
                        latencyMillis = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_LATENCY_MILLIS,
                            0L,
                        ),
                        uploadedBytes = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_UPLOADED_BYTES,
                            0L,
                        ),
                        downloadedBytes = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES,
                            0L,
                        ),
                        relayAssociations = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_RELAY_ASSOCIATIONS,
                            0L,
                        ),
                        datagrams = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_DATAGRAMS,
                            0L,
                        ),
                        nativeVersion = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_VERSION,
                        ),
                        abi = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_ABI,
                        ),
                    ),
                )

                TunnelContract.STATUS_DNS_PROBE_SUCCEEDED -> dispatch(
                    ConnectionEvent.DnsProbeCompleted(
                        latencyMillis = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_LATENCY_MILLIS,
                            0L,
                        ),
                        uploadedBytes = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_UPLOADED_BYTES,
                            0L,
                        ),
                        downloadedBytes = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES,
                            0L,
                        ),
                        relayAssociations = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_RELAY_ASSOCIATIONS,
                            0L,
                        ),
                        datagrams = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_DATAGRAMS,
                            0L,
                        ),
                        queries = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_DNS_QUERIES,
                            0L,
                        ),
                        responses = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_DNS_RESPONSES,
                            0L,
                        ),
                        answer = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_PROBE_DNS_ANSWER,
                        ).orEmpty(),
                        nativeVersion = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_VERSION,
                        ),
                        abi = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_ABI,
                        ),
                    ),
                )

                TunnelContract.STATUS_STRATEGY_PROBE_SUCCEEDED -> dispatch(
                    ConnectionEvent.StrategyProbeCompleted(
                        strategyId = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_STRATEGY_ID,
                        ).orEmpty(),
                        segments = statusIntent.getIntExtra(
                            TunnelContract.EXTRA_STRATEGY_SEGMENTS,
                            0,
                        ),
                        splitOffset = statusIntent.getIntExtra(
                            TunnelContract.EXTRA_STRATEGY_SPLIT_OFFSET,
                            0,
                        ),
                        latencyMillis = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_LATENCY_MILLIS,
                            0L,
                        ),
                        uploadedBytes = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_UPLOADED_BYTES,
                            0L,
                        ),
                        downloadedBytes = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES,
                            0L,
                        ),
                        relayConnections = statusIntent.getLongExtra(
                            TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS,
                            0L,
                        ),
                        nativeVersion = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_VERSION,
                        ),
                        abi = statusIntent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_ABI,
                        ),
                    ),
                )

                TunnelContract.STATUS_STRATEGY_EVALUATION_COMPLETED -> dispatch(
                    statusIntent.toStrategyEvaluationEvent(),
                )

                TunnelContract.STATUS_STOPPED -> dispatch(ConnectionEvent.TunnelStopped)
                TunnelContract.STATUS_ERROR -> dispatch(
                    ConnectionEvent.Failed(
                        statusIntent.getStringExtra(TunnelContract.EXTRA_ERROR)
                            ?: "Неизвестная ошибка локального TUN",
                    ),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerTunnelStatusReceiver()
        inspectNativeBridge()

        setContent {
            ConnectXTheme {
                val evidence = externalEvidenceState.value
                val baseUiState = connectionState.value
                Box(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        uiState = baseUiState.forExternalEvidence(evidence.status),
                        onToggle = ::toggleTunnel,
                        onNativeSelfTest = {
                            requestTunnelPermission(EngineMode.NATIVE_SELF_TEST)
                        },
                        onNativeTcpProbe = {
                            requestTunnelPermission(EngineMode.NATIVE_TCP_PROBE)
                        },
                        onNativeUdpProbe = {
                            requestTunnelPermission(EngineMode.NATIVE_UDP_PROBE)
                        },
                        onNativeDnsProbe = {
                            requestTunnelPermission(EngineMode.NATIVE_DNS_PROBE)
                        },
                        onNativeTlsSplitProbe = {
                            requestTunnelPermission(EngineMode.NATIVE_TLS_SPLIT_PROBE)
                        },
                        onStrategyEvaluation = {
                            requestTunnelPermission(EngineMode.NATIVE_STRATEGY_EVALUATION)
                        },
                    )
                    ExternalTlsEvidencePanel(
                        state = evidence,
                        globalBusy = baseUiState.state !in setOf(
                            ConnectionState.OFF,
                            ConnectionState.ERROR,
                        ),
                        onPresetSelected = ::selectEvidencePreset,
                        onHostnameChanged = ::updateEvidenceHostname,
                        onStart = ::requestExternalTlsEvidence,
                        onStop = ::stopExternalTlsEvidenceService,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 54.dp, end = 16.dp),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(tunnelStatusReceiver)
        super.onDestroy()
    }

    private fun toggleTunnel() {
        if (externalEvidenceState.value.isBusy) {
            stopExternalTlsEvidenceService()
            return
        }

        when (connectionState.value.state) {
            ConnectionState.LOCAL_TUN_ACTIVE -> stopTunnelService()
            ConnectionState.STARTING,
            ConnectionState.PERMISSION_REQUIRED,
            ConnectionState.STOPPING,
            -> Unit

            ConnectionState.OFF,
            ConnectionState.ERROR,
            -> requestTunnelPermission(EngineMode.FOUNDATION)
        }
    }

    private fun requestTunnelPermission(mode: EngineMode) {
        if (externalEvidenceState.value.isBusy) return

        pendingVpnRequest = PendingVpnRequest.TUNNEL
        pendingMode = mode
        dispatch(ConnectionEvent.StartRequested(mode))
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            pendingVpnRequest = PendingVpnRequest.NONE
            dispatch(ConnectionEvent.PermissionGranted)
            startTunnelService(mode)
        } else {
            dispatch(ConnectionEvent.PermissionRequired)
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun requestExternalTlsEvidence() {
        if (
            connectionState.value.state !in setOf(ConnectionState.OFF, ConnectionState.ERROR) ||
            externalEvidenceState.value.isBusy
        ) {
            return
        }

        val validation = ExternalTlsEvidenceTarget.validateHostname(
            externalEvidenceState.value.hostnameInput,
        )
        if (validation !is HostnameValidationResult.Valid) {
            externalEvidenceState.value = externalEvidenceState.value.copy(
                status = ExternalTlsEvidenceStatus.ERROR,
                error = "Hostname отклонён: ${validation.reason.name}",
            )
            return
        }

        pendingEvidenceHostname = validation.normalizedHostname
        pendingVpnRequest = PendingVpnRequest.EXTERNAL_TLS_EVIDENCE
        externalEvidenceState.value = ExternalTlsEvidenceUiState(
            hostnameInput = externalEvidenceState.value.hostnameInput,
            selectedPresetId = externalEvidenceState.value.selectedPresetId,
            status = ExternalTlsEvidenceStatus.REQUESTING_PERMISSION,
            normalizedHostname = validation.normalizedHostname,
        )

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            pendingVpnRequest = PendingVpnRequest.NONE
            startExternalTlsEvidenceService()
        } else {
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun selectEvidencePreset(preset: ExternalTlsEvidencePreset) {
        if (externalEvidenceState.value.isBusy) return
        externalEvidenceState.value = ExternalTlsEvidenceUiState(
            hostnameInput = preset.hostname ?: externalEvidenceState.value.hostnameInput,
            selectedPresetId = preset.id,
        )
    }

    private fun updateEvidenceHostname(hostname: String) {
        if (externalEvidenceState.value.isBusy) return
        externalEvidenceState.value = ExternalTlsEvidenceUiState(
            hostnameInput = hostname.take(MAX_HOSTNAME_INPUT_CHARS),
            selectedPresetId = ExternalTlsEvidencePreset.CUSTOM.id,
        )
    }

    private fun startTunnelService(mode: EngineMode) {
        runCatching {
            val serviceClass = mode.serviceClass()
            val serviceIntent = Intent(this, serviceClass).apply {
                action = TunnelContract.ACTION_START
                putExtra(TunnelContract.EXTRA_ENGINE_MODE, mode.toContractValue())
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }.onFailure { error ->
            dispatch(
                ConnectionEvent.Failed(
                    error.message ?: "Не удалось запустить локальный сервис",
                ),
            )
        }
    }

    private fun startExternalTlsEvidenceService() {
        val hostname = pendingEvidenceHostname
        if (hostname.isNullOrBlank()) {
            externalEvidenceState.value = externalEvidenceState.value.copy(
                status = ExternalTlsEvidenceStatus.ERROR,
                error = "Canonical hostname отсутствует",
            )
            return
        }

        externalEvidenceState.value = externalEvidenceState.value.copy(
            status = ExternalTlsEvidenceStatus.STARTING,
            normalizedHostname = hostname,
            error = null,
        )
        runCatching {
            val serviceIntent = Intent(
                this,
                ConnectXExternalTlsEvidenceService::class.java,
            ).apply {
                action = TunnelContract.ACTION_START
                putExtra(TunnelContract.EXTRA_EVIDENCE_HOSTNAME, hostname)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }.onFailure { error ->
            externalEvidenceState.value = externalEvidenceState.value.copy(
                status = ExternalTlsEvidenceStatus.ERROR,
                error = error.message ?: "Не удалось запустить TLS evidence service",
            )
        }
        pendingEvidenceHostname = null
    }

    private fun stopTunnelService() {
        if (externalEvidenceState.value.isBusy) {
            stopExternalTlsEvidenceService()
            return
        }

        val activeMode = connectionState.value.mode
        dispatch(ConnectionEvent.StopRequested)
        val serviceIntent = Intent(this, activeMode.serviceClass()).apply {
            action = TunnelContract.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun stopExternalTlsEvidenceService() {
        if (!externalEvidenceState.value.isBusy) return
        externalEvidenceState.value = externalEvidenceState.value.copy(
            status = ExternalTlsEvidenceStatus.STOPPING,
        )
        val serviceIntent = Intent(
            this,
            ConnectXExternalTlsEvidenceService::class.java,
        ).apply {
            action = TunnelContract.ACTION_STOP
        }
        runCatching { startService(serviceIntent) }
            .onFailure { error ->
                externalEvidenceState.value = externalEvidenceState.value.copy(
                    status = ExternalTlsEvidenceStatus.ERROR,
                    error = error.message ?: "Не удалось остановить TLS evidence service",
                )
            }
    }

    private fun handleExternalEvidenceStatus(intent: Intent) {
        when (intent.getStringExtra(TunnelContract.EXTRA_STATUS)) {
            TunnelContract.STATUS_STARTED -> {
                externalEvidenceState.value = externalEvidenceState.value.copy(
                    status = ExternalTlsEvidenceStatus.RUNNING,
                    normalizedHostname = intent.getStringExtra(
                        TunnelContract.EXTRA_EVIDENCE_HOSTNAME,
                    ) ?: externalEvidenceState.value.normalizedHostname,
                    resolvedIpv4 = intent.getStringExtra(
                        TunnelContract.EXTRA_EVIDENCE_RESOLVED_IPV4,
                    ),
                    targetPort = intent.getIntExtra(
                        TunnelContract.EXTRA_EVIDENCE_TARGET_PORT,
                        443,
                    ),
                    error = null,
                )
            }

            TunnelContract.STATUS_EXTERNAL_TLS_EVIDENCE_COMPLETED -> {
                externalEvidenceState.value = externalEvidenceState.value.copy(
                    status = ExternalTlsEvidenceStatus.COMPLETED,
                    normalizedHostname = intent.getStringExtra(
                        TunnelContract.EXTRA_EVIDENCE_HOSTNAME,
                    ) ?: externalEvidenceState.value.normalizedHostname,
                    resolvedIpv4 = intent.getStringExtra(
                        TunnelContract.EXTRA_EVIDENCE_RESOLVED_IPV4,
                    ),
                    targetPort = intent.getIntExtra(
                        TunnelContract.EXTRA_EVIDENCE_TARGET_PORT,
                        443,
                    ),
                    baselineLatencyMillis = intent.optionalNonNegativeLong(
                        TunnelContract.EXTRA_STRATEGY_BASELINE_LATENCY_MILLIS,
                    ),
                    strategyLatencyMillis = intent.optionalNonNegativeLong(
                        TunnelContract.EXTRA_STRATEGY_LATENCY_MILLIS,
                    ),
                    recoveryLatencyMillis = intent.optionalNonNegativeLong(
                        TunnelContract.EXTRA_STRATEGY_RECOVERY_LATENCY_MILLIS,
                    ),
                    baselineRecordKind = intent.getStringExtra(
                        TunnelContract.EXTRA_EVIDENCE_BASELINE_RECORD_KIND,
                    ),
                    strategyRecordKind = intent.getStringExtra(
                        TunnelContract.EXTRA_EVIDENCE_STRATEGY_RECORD_KIND,
                    ),
                    recoveryRecordKind = intent.getStringExtra(
                        TunnelContract.EXTRA_EVIDENCE_RECOVERY_RECORD_KIND,
                    ),
                    baselineSuccesses = intent.getIntExtra(
                        TunnelContract.EXTRA_EVIDENCE_BASELINE_SUCCESSES,
                        0,
                    ),
                    baselineFailures = intent.getIntExtra(
                        TunnelContract.EXTRA_EVIDENCE_BASELINE_FAILURES,
                        0,
                    ),
                    strategySuccesses = intent.getIntExtra(
                        TunnelContract.EXTRA_EVIDENCE_STRATEGY_SUCCESSES,
                        0,
                    ),
                    strategyFailures = intent.getIntExtra(
                        TunnelContract.EXTRA_EVIDENCE_STRATEGY_FAILURES,
                        0,
                    ),
                    recoverySuccesses = intent.getIntExtra(
                        TunnelContract.EXTRA_EVIDENCE_RECOVERY_SUCCESSES,
                        0,
                    ),
                    recoveryFailures = intent.getIntExtra(
                        TunnelContract.EXTRA_EVIDENCE_RECOVERY_FAILURES,
                        0,
                    ),
                    decision = intent.getStringExtra(
                        TunnelContract.EXTRA_STRATEGY_DECISION,
                    ),
                    reason = intent.getStringExtra(
                        TunnelContract.EXTRA_STRATEGY_REASON,
                    ),
                    gateState = intent.getStringExtra(
                        TunnelContract.EXTRA_STRATEGY_GATE_STATE,
                    ),
                    error = null,
                )
            }

            TunnelContract.STATUS_STOPPED -> {
                externalEvidenceState.value = ExternalTlsEvidenceUiState(
                    hostnameInput = externalEvidenceState.value.hostnameInput,
                    selectedPresetId = externalEvidenceState.value.selectedPresetId,
                )
            }

            TunnelContract.STATUS_ERROR -> {
                externalEvidenceState.value = externalEvidenceState.value.copy(
                    status = ExternalTlsEvidenceStatus.ERROR,
                    error = intent.getStringExtra(TunnelContract.EXTRA_ERROR)
                        ?: "Неизвестная ошибка внешней TLS-проверки",
                )
            }
        }
    }

    private fun EngineMode.serviceClass(): Class<*> = when (this) {
        EngineMode.NATIVE_DNS_PROBE -> ConnectXDnsProbeService::class.java
        EngineMode.NATIVE_STRATEGY_EVALUATION ->
            ConnectXStrategyEvaluationService::class.java
        else -> ConnectXTunnelService::class.java
    }

    private fun inspectNativeBridge() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI
        if (!NativeTunBridge.isAvailable()) {
            dispatch(
                ConnectionEvent.NativeAvailabilityChecked(
                    available = false,
                    abi = abi,
                    error = NativeTunBridge.loadError()
                        ?: "Native bridge не загрузился для ABI $abi",
                ),
            )
            return
        }

        NativeTunBridge.version()
            .onSuccess { version ->
                dispatch(
                    ConnectionEvent.NativeAvailabilityChecked(
                        available = true,
                        version = version,
                        abi = abi,
                    ),
                )
            }
            .onFailure { error ->
                dispatch(
                    ConnectionEvent.NativeAvailabilityChecked(
                        available = false,
                        abi = abi,
                        error = error.message ?: "JNI version self-check завершился ошибкой",
                    ),
                )
            }
    }

    private fun registerTunnelStatusReceiver() {
        ContextCompat.registerReceiver(
            this,
            tunnelStatusReceiver,
            IntentFilter(TunnelContract.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun dispatch(event: ConnectionEvent) {
        connectionState.value = ConnectionStateReducer.reduce(
            current = connectionState.value,
            event = event,
        )
    }

    private enum class PendingVpnRequest {
        NONE,
        TUNNEL,
        EXTERNAL_TLS_EVIDENCE,
    }

    private companion object {
        const val MAX_HOSTNAME_INPUT_CHARS = 253
    }
}

private fun ConnectionUiState.forExternalEvidence(
    status: ExternalTlsEvidenceStatus,
): ConnectionUiState = when (status) {
    ExternalTlsEvidenceStatus.REQUESTING_PERMISSION,
    ExternalTlsEvidenceStatus.STARTING,
    -> copy(
        state = ConnectionState.STARTING,
        mode = EngineMode.NATIVE_STRATEGY_EVALUATION,
    )

    ExternalTlsEvidenceStatus.RUNNING -> copy(
        state = ConnectionState.LOCAL_TUN_ACTIVE,
        mode = EngineMode.NATIVE_STRATEGY_EVALUATION,
    )

    ExternalTlsEvidenceStatus.STOPPING -> copy(
        state = ConnectionState.STOPPING,
        mode = EngineMode.NATIVE_STRATEGY_EVALUATION,
    )

    ExternalTlsEvidenceStatus.IDLE,
    ExternalTlsEvidenceStatus.COMPLETED,
    ExternalTlsEvidenceStatus.ERROR,
    -> this
}

private fun Intent.toStrategyEvaluationEvent(): ConnectionEvent.StrategyEvaluationCompleted =
    ConnectionEvent.StrategyEvaluationCompleted(
        strategyId = getStringExtra(TunnelContract.EXTRA_STRATEGY_ID).orEmpty(),
        segments = getIntExtra(TunnelContract.EXTRA_STRATEGY_SEGMENTS, 0),
        splitOffset = getIntExtra(TunnelContract.EXTRA_STRATEGY_SPLIT_OFFSET, 0),
        decision = getStringExtra(TunnelContract.EXTRA_STRATEGY_DECISION).orEmpty(),
        reason = getStringExtra(TunnelContract.EXTRA_STRATEGY_REASON).orEmpty(),
        baselineLatencyMillis = optionalNonNegativeLong(
            TunnelContract.EXTRA_STRATEGY_BASELINE_LATENCY_MILLIS,
        ),
        strategyLatencyMillis = optionalNonNegativeLong(
            TunnelContract.EXTRA_STRATEGY_LATENCY_MILLIS,
        ),
        recoveryLatencyMillis = optionalNonNegativeLong(
            TunnelContract.EXTRA_STRATEGY_RECOVERY_LATENCY_MILLIS,
        ),
        latencyDeltaMillis = optionalLong(
            TunnelContract.EXTRA_STRATEGY_LATENCY_DELTA_MILLIS,
            missingValue = Long.MIN_VALUE,
        ),
        allowedStrategyLatencyMillis = optionalNonNegativeLong(
            TunnelContract.EXTRA_STRATEGY_ALLOWED_LATENCY_MILLIS,
        ),
        baselineFailure = getStringExtra(TunnelContract.EXTRA_STRATEGY_BASELINE_FAILURE),
        strategyFailure = getStringExtra(TunnelContract.EXTRA_STRATEGY_PHASE_FAILURE),
        recoveryFailure = getStringExtra(TunnelContract.EXTRA_STRATEGY_RECOVERY_FAILURE),
        uploadedBytes = getLongExtra(TunnelContract.EXTRA_PROBE_UPLOADED_BYTES, 0L),
        downloadedBytes = getLongExtra(TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES, 0L),
        relayConnections = getLongExtra(TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS, 0L),
        gateState = getStringExtra(TunnelContract.EXTRA_STRATEGY_GATE_STATE).orEmpty(),
        cooldownUntilElapsedMillis = optionalNonNegativeLong(
            TunnelContract.EXTRA_STRATEGY_COOLDOWN_UNTIL_MILLIS,
        ),
        nativeVersion = getStringExtra(TunnelContract.EXTRA_NATIVE_VERSION),
        abi = getStringExtra(TunnelContract.EXTRA_NATIVE_ABI),
    )

private fun EngineMode.toContractValue(): String = when (this) {
    EngineMode.FOUNDATION -> TunnelContract.MODE_FOUNDATION
    EngineMode.NATIVE_SELF_TEST -> TunnelContract.MODE_NATIVE_SELF_TEST
    EngineMode.NATIVE_TCP_PROBE -> TunnelContract.MODE_NATIVE_TCP_PROBE
    EngineMode.NATIVE_UDP_PROBE -> TunnelContract.MODE_NATIVE_UDP_PROBE
    EngineMode.NATIVE_DNS_PROBE -> TunnelContract.MODE_NATIVE_DNS_PROBE
    EngineMode.NATIVE_TLS_SPLIT_PROBE -> TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE
    EngineMode.NATIVE_STRATEGY_EVALUATION ->
        TunnelContract.MODE_NATIVE_STRATEGY_EVALUATION
}

private fun Intent.readEngineMode(): EngineMode = when (
    getStringExtra(TunnelContract.EXTRA_ENGINE_MODE)
) {
    TunnelContract.MODE_NATIVE_SELF_TEST -> EngineMode.NATIVE_SELF_TEST
    TunnelContract.MODE_NATIVE_TCP_PROBE -> EngineMode.NATIVE_TCP_PROBE
    TunnelContract.MODE_NATIVE_UDP_PROBE -> EngineMode.NATIVE_UDP_PROBE
    TunnelContract.MODE_NATIVE_DNS_PROBE -> EngineMode.NATIVE_DNS_PROBE
    TunnelContract.MODE_NATIVE_TLS_SPLIT_PROBE -> EngineMode.NATIVE_TLS_SPLIT_PROBE
    TunnelContract.MODE_NATIVE_STRATEGY_EVALUATION ->
        EngineMode.NATIVE_STRATEGY_EVALUATION
    else -> EngineMode.FOUNDATION
}

private fun Intent.optionalNonNegativeLong(name: String): Long? =
    getLongExtra(name, -1L).takeIf { it >= 0L }

private fun Intent.optionalLong(name: String, missingValue: Long): Long? =
    getLongExtra(name, missingValue).takeIf { it != missingValue }
