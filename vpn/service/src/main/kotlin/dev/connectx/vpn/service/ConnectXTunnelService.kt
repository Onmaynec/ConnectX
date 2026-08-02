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
import java.io.IOException

class ConnectXTunnelService : VpnService() {
    private var tunnelDescriptor: ParcelFileDescriptor? = null

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
        closeTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startTunnel() {
        promoteToForeground()

        if (tunnelDescriptor != null) {
            publishStatus(TunnelContract.STATUS_STARTED)
            return
        }

        try {
            tunnelDescriptor = Builder()
                .setSession("ConnectX Foundation")
                .setMtu(DEFAULT_MTU)
                .addAddress(LOCAL_TUN_ADDRESS, LOCAL_TUN_PREFIX)
                // Foundation intentionally captures TEST-NET-1 only. Real traffic is not
                // intercepted until a tested userspace packet engine is implemented.
                .addRoute(TEST_ROUTE, TEST_ROUTE_PREFIX)
                .setBlocking(false)
                .establish()
                ?: error("Android не создал локальный TUN-интерфейс")

            publishStatus(TunnelContract.STATUS_STARTED)
        } catch (error: Exception) {
            closeTunnel()
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                error = error.message ?: "Не удалось создать локальный TUN",
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopTunnelAndService() {
        closeTunnel()
        publishStatus(TunnelContract.STATUS_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTunnel() {
        val descriptor = tunnelDescriptor
        tunnelDescriptor = null
        try {
            descriptor?.close()
        } catch (_: IOException) {
            // The descriptor is already detached from the service state. There is no
            // safe recovery action required during shutdown.
        }
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
            .setContentText("Локальный TUN активен · режим Foundation")
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
