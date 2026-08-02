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
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import dev.connectx.app.home.HomeScreen
import dev.connectx.core.designsystem.ConnectXTheme
import dev.connectx.core.model.ConnectionEvent
import dev.connectx.core.model.ConnectionState
import dev.connectx.core.model.ConnectionStateReducer
import dev.connectx.core.model.ConnectionUiState
import dev.connectx.core.model.EngineMode
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.service.ConnectXTunnelService

class MainActivity : ComponentActivity() {
    private val connectionState = mutableStateOf(ConnectionUiState())
    private var pendingMode: EngineMode = EngineMode.FOUNDATION

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            dispatch(ConnectionEvent.PermissionGranted)
            startTunnelService(pendingMode)
        } else {
            dispatch(ConnectionEvent.PermissionDenied)
            pendingMode = EngineMode.FOUNDATION
        }
    }

    private val tunnelStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val mode = intent?.readEngineMode() ?: EngineMode.FOUNDATION
            when (intent?.getStringExtra(TunnelContract.EXTRA_STATUS)) {
                TunnelContract.STATUS_STARTED -> dispatch(
                    ConnectionEvent.TunnelStarted(
                        mode = mode,
                        nativeVersion = intent.getStringExtra(
                            TunnelContract.EXTRA_NATIVE_VERSION,
                        ),
                        abi = intent.getStringExtra(TunnelContract.EXTRA_NATIVE_ABI),
                    ),
                )

                TunnelContract.STATUS_STOPPED -> dispatch(ConnectionEvent.TunnelStopped)
                TunnelContract.STATUS_ERROR -> dispatch(
                    ConnectionEvent.Failed(
                        intent.getStringExtra(TunnelContract.EXTRA_ERROR)
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
                HomeScreen(
                    uiState = connectionState.value,
                    onToggle = ::toggleTunnel,
                    onNativeSelfTest = {
                        requestTunnelPermission(EngineMode.NATIVE_SELF_TEST)
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(tunnelStatusReceiver)
        super.onDestroy()
    }

    private fun toggleTunnel() {
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
        pendingMode = mode
        dispatch(ConnectionEvent.StartRequested(mode))
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            dispatch(ConnectionEvent.PermissionGranted)
            startTunnelService(mode)
        } else {
            dispatch(ConnectionEvent.PermissionRequired)
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun startTunnelService(mode: EngineMode) {
        runCatching {
            val serviceIntent = Intent(this, ConnectXTunnelService::class.java).apply {
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

    private fun stopTunnelService() {
        dispatch(ConnectionEvent.StopRequested)
        val serviceIntent = Intent(this, ConnectXTunnelService::class.java).apply {
            action = TunnelContract.ACTION_STOP
        }
        startService(serviceIntent)
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
}

private fun EngineMode.toContractValue(): String = when (this) {
    EngineMode.FOUNDATION -> TunnelContract.MODE_FOUNDATION
    EngineMode.NATIVE_SELF_TEST -> TunnelContract.MODE_NATIVE_SELF_TEST
}

private fun Intent.readEngineMode(): EngineMode = when (
    getStringExtra(TunnelContract.EXTRA_ENGINE_MODE)
) {
    TunnelContract.MODE_NATIVE_SELF_TEST -> EngineMode.NATIVE_SELF_TEST
    else -> EngineMode.FOUNDATION
}
