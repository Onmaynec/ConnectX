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

data class ConnectionUiState(
    val state: ConnectionState = ConnectionState.OFF,
    val mode: EngineMode = EngineMode.FOUNDATION,
    val diagnostics: NativeBridgeDiagnostics = NativeBridgeDiagnostics(),
    val probe: TcpProbeDiagnostics = TcpProbeDiagnostics(),
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
                    EngineMode.FOUNDATION -> current.diagnostics.lastResult
                },
            ),
            probe = current.probe.copy(
                running = event.mode == EngineMode.NATIVE_TCP_PROBE,
                error = null,
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
                    EngineMode.FOUNDATION -> current.diagnostics.lastResult
                },
            ),
            probe = current.probe.copy(running = false),
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
            errorMessage = event.message,
        )
    }
}
