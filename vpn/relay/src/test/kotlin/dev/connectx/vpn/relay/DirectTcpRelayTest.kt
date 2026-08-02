package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTcpRelayTest {
    private val credentials = Socks5Credentials(
        username = "connectx",
        password = "relay-test-secret",
    )

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
            credentials = credentials,
        )

        try {
            val relayPort = relay.start()
            Socket(loopback, relayPort).use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                authenticate(input, output, credentials)

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
            val stats = awaitTransferredBytes(relay)
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
            credentials = credentials,
        )

        try {
            val relayPort = relay.start()
            Socket(InetAddress.getLoopbackAddress(), relayPort).use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                authenticate(input, output, credentials)

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

    @Test
    fun `invalid credentials cannot reach socket protector`() {
        val protectCalled = AtomicBoolean(false)
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector {
                protectCalled.set(true)
                true
            },
            credentials = credentials,
        )

        try {
            val relayPort = relay.start()
            Socket(InetAddress.getLoopbackAddress(), relayPort).use { client ->
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())

                output.write(byteArrayOf(0x05, 0x01, 0x02))
                output.flush()
                assertArrayEquals(
                    byteArrayOf(0x05, 0x02),
                    ByteArray(2).also(input::readFully),
                )

                writeCredentials(
                    output = output,
                    username = credentials.username,
                    password = "invalid-password",
                )
                assertArrayEquals(
                    byteArrayOf(0x01, 0x01),
                    ByteArray(2).also(input::readFully),
                )
            }

            assertFalse(protectCalled.get())
            assertTrue(relay.stats().failedConnections > 0)
        } finally {
            relay.close()
        }
    }

    private fun awaitTransferredBytes(relay: DirectTcpRelay): RelayStats {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var stats = relay.stats()
        while (
            (stats.uploadedBytes == 0L || stats.downloadedBytes == 0L) &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(10)
            stats = relay.stats()
        }
        return stats
    }

    private fun authenticate(
        input: DataInputStream,
        output: DataOutputStream,
        credentials: Socks5Credentials,
    ) {
        output.write(byteArrayOf(0x05, 0x01, 0x02))
        output.flush()
        assertArrayEquals(
            byteArrayOf(0x05, 0x02),
            ByteArray(2).also(input::readFully),
        )

        writeCredentials(
            output = output,
            username = credentials.username,
            password = credentials.password,
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0x00),
            ByteArray(2).also(input::readFully),
        )
    }

    private fun writeCredentials(
        output: DataOutputStream,
        username: String,
        password: String,
    ) {
        val usernameBytes = username.encodeToByteArray()
        val passwordBytes = password.encodeToByteArray()
        output.writeByte(0x01)
        output.writeByte(usernameBytes.size)
        output.write(usernameBytes)
        output.writeByte(passwordBytes.size)
        output.write(passwordBytes)
        output.flush()
    }
}
