package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTcpRelayTest {
    @Test
    fun `relay protects outbound socket and forwards bytes directly`() {
        val loopback = InetAddress.getLoopbackAddress()
        val echoServer = ServerSocket().apply {
            bind(InetSocketAddress(loopback, 0))
        }
        val echoThread = thread(
            start = true,
            isDaemon = true,
            name = "connectx-test-echo",
        ) {
            echoServer.accept().use { socket ->
                val buffer = ByteArray(1024)
                val read = socket.getInputStream().read(buffer)
                if (read > 0) {
                    socket.getOutputStream().write(buffer, 0, read)
                    socket.getOutputStream().flush()
                }
            }
        }

        val protectCalled = AtomicBoolean(false)
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector {
                protectCalled.set(true)
                true
            },
        )

        try {
            val relayPort = relay.start()
            Socket(loopback, relayPort).use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())

                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertArrayEquals(
                    byteArrayOf(0x05, 0x00),
                    ByteArray(2).also(input::readFully),
                )

                val targetPort = echoServer.localPort
                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x01,
                        127,
                        0,
                        0,
                        1,
                        (targetPort ushr 8).toByte(),
                        targetPort.toByte(),
                    ),
                )
                output.flush()

                val reply = ByteArray(10)
                input.readFully(reply)
                assertEquals(0x05, reply[0].toInt() and 0xFF)
                assertEquals(0x00, reply[1].toInt() and 0xFF)

                val payload = "connectx-direct-relay".encodeToByteArray()
                output.write(payload)
                output.flush()

                val echoed = ByteArray(payload.size)
                input.readFully(echoed)
                assertArrayEquals(payload, echoed)
            }

            echoThread.join(2_000)
            val stats = relay.stats()
            assertTrue(protectCalled.get())
            assertEquals(1L, stats.acceptedConnections)
            assertTrue(stats.uploadedBytes > 0)
            assertTrue(stats.downloadedBytes > 0)
        } finally {
            relay.close()
            echoServer.close()
        }
    }

    @Test
    fun `failed socket protection prevents outbound connection`() {
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector { false },
        )

        try {
            val relayPort = relay.start()
            Socket(InetAddress.getLoopbackAddress(), relayPort).use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())

                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                input.readFully(ByteArray(2))

                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x01,
                        127,
                        0,
                        0,
                        1,
                        0x00,
                        0x50,
                    ),
                )
                output.flush()

                val reply = ByteArray(10)
                input.readFully(reply)
                assertEquals(
                    Socks5Protocol.REPLY_GENERAL_FAILURE,
                    reply[1].toInt() and 0xFF,
                )
            }

            assertTrue(relay.stats().failedConnections > 0)
        } finally {
            relay.close()
        }
    }
}
