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
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.nativebridge.NativeTunSession
import dev.connectx.vpn.relay.DirectTcpRelay
import dev.connectx.vpn.relay.SocketProtector
import dev.connectx.vpn.relay.Socks5Credentials
import java.io.IOException

class ConnectXTunnelService : VpnService() {
    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var directTcpRelay: DirectTcpRelay? = null
    private var relayCredentials: Socks5Credentials? = null
    private var nativeTunSession: NativeTunSession? = null
    private var activeMode: String = TunnelContract.MODE_FOUNDATION
    private var nativeVersion: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TunnelContract.ACTION_STOP -> stopTunnelAndService()
            TunnelContract.ACTION_START, null -> startTunnel(
                requestedMode = intent
                    ?.getStringExtra(TunnelContract.EXTRA_ENGINE_MODE)
                    .orEmpty()
                    .ifBlank { TunnelContract.MODE_FOUNDATION },
            )
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnelAndService()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnelResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startTunnel(requestedMode: String) {
        val mode = validateMode(requestedMode)
        promoteToForeground(mode)

        if (isModeAlreadyRunning(mode)) {
            publishStartedStatus()
            return
        }

        if (tunnelDescriptor != null || directTcpRelay != null || nativeTunSession != null) {
            closeTunnelResources()
        }

        try {
            val relayPort = startDirectTcpRelay()
            val tunnel = establishTestTunnel()
            tunnelDescriptor = tunnel

            var startedNativeVersion: String? = null
            if (mode == TunnelContract.MODE_NATIVE_SELF_TEST) {
                startedNativeVersion = startNativeSelfTest(
                    tunnel = tunnel,
                    relayPort = relayPort,
                )
            }

            activeMode = mode
            nativeVersion = startedNativeVersion
            publishStartedStatus()
            updateForegroundNotification()
        } catch (error: Throwable) {
            val cleanupError = closeTunnelResources()
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                mode = mode,
                error = buildFailureMessage(error, cleanupError),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun validateMode(mode: String): String = when (mode) {
        TunnelContract.MODE_FOUNDATION,
        TunnelContract.MODE_NATIVE_SELF_TEST,
        -> mode

        else -> error("Неизвестный режим локального движка")
    }

    private fun isModeAlreadyRunning(mode: String): Boolean {
        if (tunnelDescriptor == null || directTcpRelay == null || activeMode != mode) {
            return false
        }
        return mode != TunnelContract.MODE_NATIVE_SELF_TEST ||
            (nativeTunSession != null && runCatching { NativeTunBridge.isRunning() }.getOrDefault(false))
    }

    private fun establishTestTunnel(): ParcelFileDescriptor = Builder()
        .setSession("ConnectX v0.2 alpha self-test")
        .setMtu(DEFAULT_MTU)
        .addAddress(LOCAL_TUN_ADDRESS, LOCAL_TUN_PREFIX)
        // alpha.3 intentionally keeps only TEST-NET-1. Ordinary application
        // traffic cannot enter the unfinished native stack.
        .addRoute(TEST_ROUTE, TEST_ROUTE_PREFIX)
        .setBlocking(false)
        .establish()
        ?: error("Android не создал локальный TUN-интерфейс")

    private fun startDirectTcpRelay(): Int {
        directTcpRelay?.let { relay ->
            return relay.stats().listeningPort
        }

        val credentials = Socks5Credentials.random()
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector { socket -> protect(socket) },
            credentials = credentials,
        )
        val port = relay.start()
        check(port in 1..65535) { "Локальный TCP relay не открыл порт" }

        relayCredentials = credentials
        directTcpRelay = relay
        return port
    }

    private fun startNativeSelfTest(
        tunnel: ParcelFileDescriptor,
        relayPort: Int,
    ): String {
        check(NativeTunBridge.isAvailable()) {
            NativeTunBridge.loadError()
                ?: "Native bridge недоступен для ABI этого устройства"
        }

        val version = NativeTunBridge.version().getOrElse { error ->
            throw IllegalStateException(
                "Не удалось выполнить JNI version self-check",
                error,
            )
        }
        val credentials = checkNotNull(relayCredentials) {
            "Отсутствуют временные credentials локального relay"
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
            nativeTunSession = session
            return version
        } catch (error: Throwable) {
            runCatching { session.close() }
            throw error
        }
    }

    private fun stopTunnelAndService() {
        val cleanupError = closeTunnelResources()
        if (cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_STOPPED,
                mode = activeMode,
            )
        } else {
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                mode = activeMode,
                error = "Ресурсы остановлены с ошибкой: ${cleanupError.message ?: cleanupError::class.java.simpleName}",
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Closes native stack first, then the Android TUN and finally the relay. */
    private fun closeTunnelResources(): Throwable? {
        var firstError: Throwable? = null

        val nativeSession = nativeTunSession
        nativeTunSession = null
        runCatching { nativeSession?.close() }
            .onFailure { error -> firstError = error }

        val descriptor = tunnelDescriptor
        tunnelDescriptor = null
        try {
            descriptor?.close()
        } catch (error: IOException) {
            if (firstError == null) firstError = error
        }

        val relay = directTcpRelay
        directTcpRelay = null
        relayCredentials = null
        runCatching { relay?.close() }
            .onFailure { error -> if (firstError == null) firstError = error }

        nativeVersion = null
        activeMode = TunnelContract.MODE_FOUNDATION
        return firstError
    }

    private fun buildFailureMessage(
        primary: Throwable,
        cleanup: Throwable?,
    ): String {
        val primaryMessage = primary.message ?: primary::class.java.simpleName
        return if (cleanup == null) {
            primaryMessage
        } else {
            "$primaryMessage; ошибка очистки: ${cleanup.message ?: cleanup::class.java.simpleName}"
        }
    }

    private fun promoteToForeground(mode: String) {
        val notification = buildNotification(mode)
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

    private fun updateForegroundNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(activeMode))
    }

    private fun buildNotification(mode: String): Notification {
        val stopIntent = Intent(this, ConnectXTunnelService::class.java).apply {
            action = TunnelContract.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val detail = if (mode == TunnelContract.MODE_NATIVE_SELF_TEST) {
            "Native bridge активен · только TEST-NET"
        } else {
            "Защищённый TCP relay готов · тестовый маршрут"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("ConnectX")
            .setContentText(detail)
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

    private fun publishStartedStatus() {
        publishStatus(
            status = TunnelContract.STATUS_STARTED,
            mode = activeMode,
            nativeVersion = nativeVersion,
        )
    }

    private fun publishStatus(
        status: String,
        mode: String,
        error: String? = null,
        nativeVersion: String? = null,
    ) {
        val statusIntent = Intent(TunnelContract.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(TunnelContract.EXTRA_STATUS, status)
            putExtra(TunnelContract.EXTRA_ENGINE_MODE, mode)
            putExtra(TunnelContract.EXTRA_NATIVE_ABI, currentAbi())
            nativeVersion?.let { putExtra(TunnelContract.EXTRA_NATIVE_VERSION, it) }
            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }
        }
        sendBroadcast(statusIntent)
    }

    private fun currentAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "connectx_tunnel"
        const val NOTIFICATION_ID = 1001
        const val STOP_REQUEST_CODE = 1002

        const val DEFAULT_MTU = 1500
        const val LOCAL_TUN_ADDRESS = "10.222.0.2"
        const val LOCAL_TUN_PREFIX = 32
        const val TEST_ROUTE = "192.0.2.0"
        const val TEST_ROUTE_PREFIX = 24
        const val RELAY_HOST = "127.0.0.1"
    }
}
