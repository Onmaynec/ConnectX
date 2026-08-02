package dev.connectx.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.relay.DirectTcpRelay
import dev.connectx.vpn.relay.SocketProtector
import dev.connectx.vpn.relay.Socks5Credentials
import java.io.IOException

class ConnectXTunnelService : VpnService() {
    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var directTcpRelay: DirectTcpRelay? = null
    private var relayCredentials: Socks5Credentials? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TunnelContract.ACTION_STOP -> stopTunnelAndService()
            TunnelContract.ACTION_START, null -> startTunnel()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnelAndService()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnelAndRelay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startTunnel() {
        promoteToForeground()

        if (tunnelDescriptor != null && directTcpRelay != null) {
            publishStatus(TunnelContract.STATUS_STARTED)
            return
        }

        try {
            startDirectTcpRelay()

            tunnelDescriptor = Builder()
                .setSession("ConnectX v0.2 alpha")
                .setMtu(DEFAULT_MTU)
                .addAddress(LOCAL_TUN_ADDRESS, LOCAL_TUN_PREFIX)
                // v0.2.0-alpha.1 keeps TEST-NET-1 routing. The direct TCP relay is
                // functional, but the source-built tun2socks bridge is the next gate
                // before ordinary application traffic can safely enter the engine.
                .addRoute(TEST_ROUTE, TEST_ROUTE_PREFIX)
                .setBlocking(false)
                .establish()
                ?: error("Android не создал локальный TUN-интерфейс")

            publishStatus(TunnelContract.STATUS_STARTED)
        } catch (error: Exception) {
            closeTunnelAndRelay()
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                error = error.message ?: "Не удалось запустить локальный сетевой движок",
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startDirectTcpRelay(): Int {
        directTcpRelay?.let { relay ->
            return relay.stats().listeningPort
        }

        val credentials = Socks5Credentials.random()
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector { socket ->
                protect(socket)
            },
            credentials = credentials,
        )
        val port = relay.start()
        relayCredentials = credentials
        directTcpRelay = relay
        return port
    }

    private fun stopTunnelAndService() {
        closeTunnelAndRelay()
        publishStatus(TunnelContract.STATUS_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTunnelAndRelay() {
        val descriptor = tunnelDescriptor
        tunnelDescriptor = null
        try {
            descriptor?.close()
        } catch (_: IOException) {
            // The descriptor is already detached from the service state.
        }

        val relay = directTcpRelay
        directTcpRelay = null
        relayCredentials = null
        relay?.close()
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
        val stopIntent = Intent(this, ConnectXTunnelService::class.java).apply {
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
            .setContentText("Защищённый TCP relay готов · тестовый маршрут")
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

    private fun publishStatus(status: String, error: String? = null) {
        val statusIntent = Intent(TunnelContract.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(TunnelContract.EXTRA_STATUS, status)
            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }
        }
        sendBroadcast(statusIntent)
    }

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "connectx_tunnel"
        const val NOTIFICATION_ID = 1001
        const val STOP_REQUEST_CODE = 1002

        const val DEFAULT_MTU = 1500
        const val LOCAL_TUN_ADDRESS = "10.222.0.2"
        const val LOCAL_TUN_PREFIX = 32
        const val TEST_ROUTE = "192.0.2.0"
        const val TEST_ROUTE_PREFIX = 24
    }
}
