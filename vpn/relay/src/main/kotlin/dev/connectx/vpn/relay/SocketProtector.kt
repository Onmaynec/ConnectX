package dev.connectx.vpn.relay

import java.net.Socket

/**
 * Protects an outbound socket from being routed back into the Android TUN.
 *
 * The Android implementation delegates to VpnService.protect(Socket) before
 * the socket is connected to its destination.
 */
fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

data class RelayStats(
    val listeningPort: Int,
    val activeConnections: Long,
    val acceptedConnections: Long,
    val failedConnections: Long,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
)
