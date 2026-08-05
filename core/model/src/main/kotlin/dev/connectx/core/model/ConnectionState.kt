package dev.connectx.core.model

enum class ConnectionState {
    OFF,
    PERMISSION_REQUIRED,
    STARTING,
    LOCAL_TUN_ACTIVE,
    STOPPING,
    ERROR,
}

enum class EngineMode {
    FOUNDATION,
    NATIVE_SELF_TEST,
    NATIVE_TCP_PROBE,
    NATIVE_UDP_PROBE,
    NATIVE_DNS_PROBE,
    NATIVE_TLS_SPLIT_PROBE,
    NATIVE_STRATEGY_EVALUATION,
}

data class NativeBridgeDiagnostics(
    val available: Boolean? = null,
    val version: String? = null,
    val abi: String? = null,
    val running: Boolean = false,
    val lastResult: String? = null,
)

data class TcpProbeDiagnostics(
    val running: Boolean = false,
    val lastSuccess: Boolean? = null,
    val latencyMillis: Long? = null,
    val uploadedBytes: Long? = null,
    val downloadedBytes: Long? = null,
    val relayConnections: Long? = null,
    val error: String? = null,
)

data class UdpProbeDiagnostics(
    val running: Boolean = false,
    val lastSuccess: Boolean? = null,
    val latencyMillis: Long? = null,
    val uploadedBytes: Long? = null,
    val downloadedBytes: Long? = null,
    val relayAssociations: Long? = null,
    val datagrams: Long? = null,
    val error: String? = null,
)

data class DnsProbeDiagnostics(
    val running: Boolean = false,
    val lastSuccess: Boolean? = null,
    val latencyMillis: Long? = null,
    val uploadedBytes: Long? = null,
    val downloadedBytes: Long? = null,
    val relayAssociations: Long? = null,
    val datagrams: Long? = null,
    val queries: Long? = null,
    val responses: Long? = null,
    val answer: String? = null,
    val error: String? = null,
)

data class StrategyProbeDiagnostics(
    val running: Boolean = false,
    val lastSuccess: Boolean? = null,
    val strategyId: String? = null,
    val segments: Int? = null,
    val splitOffset: Int? = null,
    val latencyMillis: Long? = null,
    val uploadedBytes: Long? = null,
    val downloadedBytes: Long? = null,
    val relayConnections: Long? = null,
    val evaluationDecision: String? = null,
    val evaluationReason: String? = null,
    val baselineLatencyMillis: Long? = null,
    val strategyLatencyMillis: Long? = null,
    val recoveryLatencyMillis: Long? = null,
    val latencyDeltaMillis: Long? = null,
    val allowedStrategyLatencyMillis: Long? = null,
    val baselineFailure: String? = null,
    val strategyFailure: String? = null,
    val recoveryFailure: String? = null,
    val gateState: String? = null,
    val cooldownUntilElapsedMillis: Long? = null,
    val error: String? = null,
)

data class ConnectionUiState(
    val state: ConnectionState = ConnectionState.OFF,
    val mode: EngineMode = EngineMode.FOUNDATION,
    val diagnostics: NativeBridgeDiagnostics = NativeBridgeDiagnostics(),
    val probe: TcpProbeDiagnostics = TcpProbeDiagnostics(),
    val udpProbe: UdpProbeDiagnostics = UdpProbeDiagnostics(),
    val dnsProbe: DnsProbeDiagnostics = DnsProbeDiagnostics(),
    val strategyProbe: StrategyProbeDiagnostics = StrategyProbeDiagnostics(),
    val errorMessage: String? = null,
)

sealed interface ConnectionEvent {
    data class StartRequested(
        val mode: EngineMode = EngineMode.FOUNDATION,
    ) : ConnectionEvent

    data object PermissionRequired : ConnectionEvent
    data object PermissionGranted : ConnectionEvent
    data object PermissionDenied : ConnectionEvent

    data class TunnelStarted(
        val mode: EngineMode,
        val nativeVersion: String? = null,
        val abi: String? = null,
    ) : ConnectionEvent

    data class ProbeCompleted(
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayConnections: Long,
        val nativeVersion: String? = null,
        val abi: String? = null,
    ) : ConnectionEvent

    data class UdpProbeCompleted(
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayAssociations: Long,
        val datagrams: Long,
        val nativeVersion: String? = null,
        val abi: String? = null,
    ) : ConnectionEvent

    data class DnsProbeCompleted(
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayAssociations: Long,
        val datagrams: Long,
        val queries: Long,
        val responses: Long,
        val answer: String,
        val nativeVersion: String? = null,
        val abi: String? = null,
    ) : ConnectionEvent

    data class StrategyProbeCompleted(
        val strategyId: String,
        val segments: Int,
        val splitOffset: Int,
        val latencyMillis: Long,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayConnections: Long,
        val nativeVersion: String? = null,
        val abi: String? = null,
    ) : ConnectionEvent

    data class StrategyEvaluationCompleted(
        val strategyId: String,
        val segments: Int,
        val splitOffset: Int,
        val decision: String,
        val reason: String,
        val baselineLatencyMillis: Long?,
        val strategyLatencyMillis: Long?,
        val recoveryLatencyMillis: Long?,
        val latencyDeltaMillis: Long?,
        val allowedStrategyLatencyMillis: Long?,
        val baselineFailure: String?,
        val strategyFailure: String?,
        val recoveryFailure: String?,
        val uploadedBytes: Long,
        val downloadedBytes: Long,
        val relayConnections: Long,
        val gateState: String,
        val cooldownUntilElapsedMillis: Long?,
        val nativeVersion: String? = null,
        val abi: String? = null,
    ) : ConnectionEvent

    data object StopRequested : ConnectionEvent
    data object TunnelStopped : ConnectionEvent

    data class NativeAvailabilityChecked(
        val available: Boolean,
        val version: String? = null,
        val abi: String? = null,
        val error: String? = null,
    ) : ConnectionEvent

    data class Failed(val message: String) : ConnectionEvent
}

object ConnectionStateReducer {
    fun reduce(
        current: ConnectionUiState,
        event: ConnectionEvent,
    ): ConnectionUiState = when (event) {
        is ConnectionEvent.StartRequested -> current.copy(
            state = ConnectionState.STARTING,
            mode = event.mode,
            probe = current.probe.copy(
                running = event.mode == EngineMode.NATIVE_TCP_PROBE,
                error = null,
            ),
            udpProbe = current.udpProbe.copy(
                running = event.mode == EngineMode.NATIVE_UDP_PROBE,
                error = null,
            ),
            dnsProbe = current.dnsProbe.copy(
                running = event.mode == EngineMode.NATIVE_DNS_PROBE,
                error = null,
            ),
            strategyProbe = strategyDiagnosticsForStart(
                current = current.strategyProbe,
                mode = event.mode,
            ),
            errorMessage = null,
        )

        ConnectionEvent.PermissionRequired -> current.copy(
            state = ConnectionState.PERMISSION_REQUIRED,
            errorMessage = null,
        )

        ConnectionEvent.PermissionGranted -> current.copy(
            state = ConnectionState.STARTING,
            errorMessage = null,
        )

        ConnectionEvent.PermissionDenied -> current.copy(
            state = ConnectionState.OFF,
            mode = EngineMode.FOUNDATION,
            probe = current.probe.copy(running = false),
            udpProbe = current.udpProbe.copy(running = false),
            dnsProbe = current.dnsProbe.copy(running = false),
            strategyProbe = current.strategyProbe.copy(running = false),
            errorMessage = "Системное разрешение не предоставлено",
        )

        is ConnectionEvent.TunnelStarted -> current.copy(
            state = ConnectionState.LOCAL_TUN_ACTIVE,
            mode = event.mode,
            diagnostics = current.diagnostics.copy(
                available = if (event.mode != EngineMode.FOUNDATION) true else current.diagnostics.available,
                version = event.nativeVersion ?: current.diagnostics.version,
                abi = event.abi ?: current.diagnostics.abi,
                running = event.mode != EngineMode.FOUNDATION,
                lastResult = when (event.mode) {
                    EngineMode.NATIVE_SELF_TEST -> "Native self-test запущен на TEST-NET"
                    EngineMode.NATIVE_TCP_PROBE -> "TCP probe проходит через TEST-NET TUN"
                    EngineMode.NATIVE_UDP_PROBE -> "UDP probe проходит через TEST-NET TUN"
                    EngineMode.NATIVE_DNS_PROBE -> "DNS probe проходит через TEST-NET TUN"
                    EngineMode.NATIVE_TLS_SPLIT_PROBE ->
                        "TLS write-split Lab проходит через TEST-NET TUN"
                    EngineMode.NATIVE_STRATEGY_EVALUATION ->
                        "A/B/A strategy evaluation проходит через TEST-NET TUN"
                    EngineMode.FOUNDATION -> current.diagnostics.lastResult
                },
            ),
            probe = current.probe.copy(
                running = event.mode == EngineMode.NATIVE_TCP_PROBE,
                error = null,
            ),
            udpProbe = current.udpProbe.copy(
                running = event.mode == EngineMode.NATIVE_UDP_PROBE,
                error = null,
            ),
            dnsProbe = current.dnsProbe.copy(
                running = event.mode == EngineMode.NATIVE_DNS_PROBE,
                error = null,
            ),
            strategyProbe = strategyDiagnosticsForStart(
                current = current.strategyProbe,
                mode = event.mode,
            ),
            errorMessage = null,
        )

        is ConnectionEvent.ProbeCompleted -> current.copy(
            state = ConnectionState.OFF,
            mode = EngineMode.FOUNDATION,
            diagnostics = current.diagnostics.copy(
                available = true,
                version = event.nativeVersion ?: current.diagnostics.version,
                abi = event.abi ?: current.diagnostics.abi,
                running = false,
                lastResult = "TCP-путь TUN → native stack → relay подтверждён",
            ),
            probe = TcpProbeDiagnostics(
                running = false,
                lastSuccess = true,
                latencyMillis = event.latencyMillis,
                uploadedBytes = event.uploadedBytes,
                downloadedBytes = event.downloadedBytes,
                relayConnections = event.relayConnections,
            ),
            udpProbe = current.udpProbe.copy(running = false),
            dnsProbe = current.dnsProbe.copy(running = false),
            strategyProbe = current.strategyProbe.copy(running = false),
            errorMessage = null,
        )

        is ConnectionEvent.UdpProbeCompleted -> current.copy(
            state = ConnectionState.OFF,
            mode = EngineMode.FOUNDATION,
            diagnostics = current.diagnostics.copy(
                available = true,
                version = event.nativeVersion ?: current.diagnostics.version,
                abi = event.abi ?: current.diagnostics.abi,
                running = false,
                lastResult = "UDP-путь TUN → native stack → relay подтверждён",
            ),
            probe = current.probe.copy(running = false),
            udpProbe = UdpProbeDiagnostics(
                running = false,
                lastSuccess = true,
                latencyMillis = event.latencyMillis,
                uploadedBytes = event.uploadedBytes,
                downloadedBytes = event.downloadedBytes,
                relayAssociations = event.relayAssociations,
                datagrams = event.datagrams,
            ),
            dnsProbe = current.dnsProbe.copy(running = false),
            strategyProbe = current.strategyProbe.copy(running = false),
            errorMessage = null,
        )

        is ConnectionEvent.DnsProbeCompleted -> current.copy(
            state = ConnectionState.OFF,
            mode = EngineMode.FOUNDATION,
            diagnostics = current.diagnostics.copy(
                available = true,
                version = event.nativeVersion ?: current.diagnostics.version,
                abi = event.abi ?: current.diagnostics.abi,
                running = false,
                lastResult = "DNS-путь TUN → native stack → relay подтверждён",
            ),
            probe = current.probe.copy(running = false),
            udpProbe = current.udpProbe.copy(running = false),
            dnsProbe = DnsProbeDiagnostics(
                running = false,
                lastSuccess = true,
                latencyMillis = event.latencyMillis,
                uploadedBytes = event.uploadedBytes,
                downloadedBytes = event.downloadedBytes,
                relayAssociations = event.relayAssociations,
                datagrams = event.datagrams,
                queries = event.queries,
                responses = event.responses,
                answer = event.answer,
            ),
            strategyProbe = current.strategyProbe.copy(running = false),
            errorMessage = null,
        )

        is ConnectionEvent.StrategyProbeCompleted -> current.copy(
            state = ConnectionState.OFF,
            mode = EngineMode.FOUNDATION,
            diagnostics = current.diagnostics.copy(
                available = true,
                version = event.nativeVersion ?: current.diagnostics.version,
                abi = event.abi ?: current.diagnostics.abi,
                running = false,
                lastResult =
                    "TLS write-split Lab: planner → TUN → native stack → relay подтверждён",
            ),
            probe = current.probe.copy(running = false),
            udpProbe = current.udpProbe.copy(running = false),
            dnsProbe = current.dnsProbe.copy(running = false),
            strategyProbe = StrategyProbeDiagnostics(
                running = false,
                lastSuccess = true,
                strategyId = event.strategyId,
                segments = event.segments,
                splitOffset = event.splitOffset,
                latencyMillis = event.latencyMillis,
                uploadedBytes = event.uploadedBytes,
                downloadedBytes = event.downloadedBytes,
                relayConnections = event.relayConnections,
            ),
            errorMessage = null,
        )

        is ConnectionEvent.StrategyEvaluationCompleted -> current.copy(
            state = ConnectionState.OFF,
            mode = EngineMode.FOUNDATION,
            diagnostics = current.diagnostics.copy(
                available = true,
                version = event.nativeVersion ?: current.diagnostics.version,
                abi = event.abi ?: current.diagnostics.abi,
                running = false,
                lastResult = "A/B/A strategy evaluation завершена: ${event.decision}",
            ),
            probe = current.probe.copy(running = false),
            udpProbe = current.udpProbe.copy(running = false),
            dnsProbe = current.dnsProbe.copy(running = false),
            strategyProbe = StrategyProbeDiagnostics(
                running = false,
                lastSuccess = true,
                strategyId = event.strategyId,
                segments = event.segments,
                splitOffset = event.splitOffset,
                uploadedBytes = event.uploadedBytes,
                downloadedBytes = event.downloadedBytes,
                relayConnections = event.relayConnections,
                evaluationDecision = event.decision,
                evaluationReason = event.reason,
                baselineLatencyMillis = event.baselineLatencyMillis,
                strategyLatencyMillis = event.strategyLatencyMillis,
                recoveryLatencyMillis = event.recoveryLatencyMillis,
                latencyDeltaMillis = event.latencyDeltaMillis,
                allowedStrategyLatencyMillis = event.allowedStrategyLatencyMillis,
                baselineFailure = event.baselineFailure,
                strategyFailure = event.strategyFailure,
                recoveryFailure = event.recoveryFailure,
                gateState = event.gateState,
                cooldownUntilElapsedMillis = event.cooldownUntilElapsedMillis,
            ),
            errorMessage = null,
        )

        ConnectionEvent.StopRequested -> current.copy(
            state = ConnectionState.STOPPING,
            errorMessage = null,
        )

        ConnectionEvent.TunnelStopped -> current.copy(
            state = ConnectionState.OFF,
            mode = EngineMode.FOUNDATION,
            diagnostics = current.diagnostics.copy(
                running = false,
                lastResult = when (current.mode) {
                    EngineMode.NATIVE_SELF_TEST ->
                        "Native self-test остановлен без перехвата обычного трафика"
                    EngineMode.NATIVE_TCP_PROBE -> "TCP probe отменён и ресурсы закрыты"
                    EngineMode.NATIVE_UDP_PROBE -> "UDP probe отменён и ресурсы закрыты"
                    EngineMode.NATIVE_DNS_PROBE -> "DNS probe отменён и ресурсы закрыты"
                    EngineMode.NATIVE_TLS_SPLIT_PROBE ->
                        "TLS write-split Lab отменён и ресурсы закрыты"
                    EngineMode.NATIVE_STRATEGY_EVALUATION ->
                        "A/B/A strategy evaluation отменена и ресурсы закрыты"
                    EngineMode.FOUNDATION -> current.diagnostics.lastResult
                },
            ),
            probe = current.probe.copy(running = false),
            udpProbe = current.udpProbe.copy(running = false),
            dnsProbe = current.dnsProbe.copy(running = false),
            strategyProbe = current.strategyProbe.copy(running = false),
            errorMessage = null,
        )

        is ConnectionEvent.NativeAvailabilityChecked -> current.copy(
            diagnostics = current.diagnostics.copy(
                available = event.available,
                version = event.version,
                abi = event.abi,
                running = false,
                lastResult = event.error ?: current.diagnostics.lastResult,
            ),
        )

        is ConnectionEvent.Failed -> current.copy(
            state = ConnectionState.ERROR,
            diagnostics = current.diagnostics.copy(
                running = false,
                lastResult = if (current.mode != EngineMode.FOUNDATION) {
                    event.message
                } else {
                    current.diagnostics.lastResult
                },
            ),
            probe = current.probe.copy(
                running = false,
                lastSuccess = if (current.mode == EngineMode.NATIVE_TCP_PROBE) false else current.probe.lastSuccess,
                error = if (current.mode == EngineMode.NATIVE_TCP_PROBE) event.message else current.probe.error,
            ),
            udpProbe = current.udpProbe.copy(
                running = false,
                lastSuccess = if (current.mode == EngineMode.NATIVE_UDP_PROBE) false else current.udpProbe.lastSuccess,
                error = if (current.mode == EngineMode.NATIVE_UDP_PROBE) event.message else current.udpProbe.error,
            ),
            dnsProbe = current.dnsProbe.copy(
                running = false,
                lastSuccess = if (current.mode == EngineMode.NATIVE_DNS_PROBE) false else current.dnsProbe.lastSuccess,
                error = if (current.mode == EngineMode.NATIVE_DNS_PROBE) event.message else current.dnsProbe.error,
            ),
            strategyProbe = if (current.mode in strategyModes) {
                StrategyProbeDiagnostics(
                    running = false,
                    lastSuccess = false,
                    error = event.message,
                )
            } else {
                current.strategyProbe.copy(running = false)
            },
            errorMessage = event.message,
        )
    }

    private fun strategyDiagnosticsForStart(
        current: StrategyProbeDiagnostics,
        mode: EngineMode,
    ): StrategyProbeDiagnostics = if (mode in strategyModes) {
        StrategyProbeDiagnostics(running = true)
    } else {
        current.copy(
            running = false,
            error = null,
        )
    }

    private val strategyModes = setOf(
        EngineMode.NATIVE_TLS_SPLIT_PROBE,
        EngineMode.NATIVE_STRATEGY_EVALUATION,
    )
}
