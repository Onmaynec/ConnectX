package dev.connectx.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
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
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.service.ConnectXTunnelService

class MainActivity : ComponentActivity() {
    private val connectionState = mutableStateOf(ConnectionUiState())

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            dispatch(ConnectionEvent.PermissionGranted)
            startTunnelService()
        } else {
            dispatch(ConnectionEvent.PermissionDenied)
        }
    }

    private val tunnelStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(TunnelContract.EXTRA_STATUS)) {
                TunnelContract.STATUS_STARTED -> dispatch(ConnectionEvent.TunnelStarted)
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

        setContent {
            ConnectXTheme {
                HomeScreen(
                    uiState = connectionState.value,
                    onToggle = ::toggleTunnel,
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
            -> requestTunnelPermission()
        }
    }

    private fun requestTunnelPermission() {
        dispatch(ConnectionEvent.StartRequested)
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            dispatch(ConnectionEvent.PermissionGranted)
            startTunnelService()
        } else {
            dispatch(ConnectionEvent.PermissionRequired)
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    private fun startTunnelService() {
        runCatching {
            val serviceIntent = Intent(this, ConnectXTunnelService::class.java).apply {
                action = TunnelContract.ACTION_START
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
