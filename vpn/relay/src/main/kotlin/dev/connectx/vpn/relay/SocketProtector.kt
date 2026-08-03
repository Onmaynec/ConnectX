package dev.connectx.vpn.relay

import java.net.DatagramSocket
import java.net.Socket

/**
 * Protects an outbound TCP socket from being routed back into the Android TUN.
 *
 * The Android implementation delegates to VpnService.protect(Socket) before
 * the socket is connected to its destination.
 */
fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

/**
 * Protects an outbound UDP socket from being routed back into the Android TUN.
 *
 * UDP support is optional and remains disabled unless a caller explicitly
 * supplies this protector together with an allow-listed UDP target resolver.
 */
fun interface DatagramSocketProtector {
    fun protect(socket: DatagramSocket): Boolean
}

data class RelayStats(
    val listeningPort: Int,
    val activeConnections: Long,
    val acceptedConnections: Long,
    val failedConnections: Long,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
    val udpAssociations: Long = 0L,
    val udpDatagrams: Long = 0L,
    val udpUploadedBytes: Long = 0L,
    val udpDownloadedBytes: Long = 0L,
)
