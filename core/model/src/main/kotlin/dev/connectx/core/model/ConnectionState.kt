package dev.connectx.core.model

enum class ConnectionState {
    OFF,
    PERMISSION_REQUIRED,
    STARTING,
    LOCAL_TUN_ACTIVE,
    STOPPING,
    ERROR,
}

data class ConnectionUiState(
    val state: ConnectionState = ConnectionState.OFF,
    val errorMessage: String? = null,
)

sealed interface ConnectionEvent {
    data object StartRequested : ConnectionEvent
    data object PermissionRequired : ConnectionEvent
    data object PermissionGranted : ConnectionEvent
    data object PermissionDenied : ConnectionEvent
    data object TunnelStarted : ConnectionEvent
    data object StopRequested : ConnectionEvent
    data object TunnelStopped : ConnectionEvent
    data class Failed(val message: String) : ConnectionEvent
}

object ConnectionStateReducer {
    fun reduce(
        current: ConnectionUiState,
        event: ConnectionEvent,
    ): ConnectionUiState = when (event) {
        ConnectionEvent.StartRequested -> current.copy(
            state = ConnectionState.STARTING,
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

        ConnectionEvent.PermissionDenied -> ConnectionUiState(
            state = ConnectionState.OFF,
            errorMessage = "Системное разрешение не предоставлено",
        )

        ConnectionEvent.TunnelStarted -> ConnectionUiState(
            state = ConnectionState.LOCAL_TUN_ACTIVE,
        )

        ConnectionEvent.StopRequested -> current.copy(
            state = ConnectionState.STOPPING,
            errorMessage = null,
        )

        ConnectionEvent.TunnelStopped -> ConnectionUiState(
            state = ConnectionState.OFF,
        )

        is ConnectionEvent.Failed -> ConnectionUiState(
            state = ConnectionState.ERROR,
            errorMessage = event.message,
        )
    }
}
