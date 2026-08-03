package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeRelayPrimitivesTest {
    @Test
    fun `exact override rewrites only reserved probe endpoint`() {
        val resolver = ExactRelayTargetOverride(
            source = RelayTarget("192.0.2.1", 18_080),
            destination = RelayTarget("127.0.0.1", 32_001),
        )

        assertEquals(
            RelayTarget("127.0.0.1", 32_001),
            resolver.resolve("192.0.2.1", 18_080),
        )
        assertEquals(
            RelayTarget("192.0.2.1", 443),
            resolver.resolve("192.0.2.1", 443),
        )
        assertEquals(
            RelayTarget("198.51.100.1", 18_080),
            resolver.resolve("198.51.100.1", 18_080),
        )
    }

    @Test
    fun `loopback echo server returns bounded payload`() {
        val echoServer = LoopbackTcpEchoServer(maxPayloadBytes = 256)
        try {
            val port = echoServer.start()
            val payload = "connectx-alpha4-probe".encodeToByteArray()

            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()

                val echoed = ByteArray(payload.size)
                DataInputStream(socket.getInputStream()).readFully(echoed)
                assertArrayEquals(payload, echoed)
            }
        } finally {
            echoServer.close()
        }
    }

    @Test
    fun `authenticated relay applies exact target override`() {
        val credentials = Socks5Credentials("connectx", "alpha4-test")
        val echoServer = LoopbackTcpEchoServer(maxPayloadBytes = 256)
        val echoPort = echoServer.start()
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector { true },
            credentials = credentials,
            targetResolver = ExactRelayTargetOverride(
                source = RelayTarget("192.0.2.1", 18_080),
                destination = RelayTarget("127.0.0.1", echoPort),
            ),
        )

        try {
            Socket(InetAddress.getLoopbackAddress(), relay.start()).use { client ->
                client.soTimeout = 2_000
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                authenticate(input, output, credentials)

                output.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x01,
                        192.toByte(),
                        0,
                        2,
                        1,
                        (18_080 ushr 8).toByte(),
                        18_080.toByte(),
                    ),
                )
                output.flush()

                val reply = ByteArray(10)
                input.readFully(reply)
                assertEquals(0, reply[1].toInt() and 0xFF)

                val payload = "through-resolver".encodeToByteArray()
                output.write(payload)
                output.flush()
                val echoed = ByteArray(payload.size)
                input.readFully(echoed)
                assertArrayEquals(payload, echoed)
            }
        } finally {
            relay.close()
            echoServer.close()
        }
    }

    private fun authenticate(
        input: DataInputStream,
        output: DataOutputStream,
        credentials: Socks5Credentials,
    ) {
        output.write(byteArrayOf(0x05, 0x01, 0x02))
        output.flush()
        assertArrayEquals(byteArrayOf(0x05, 0x02), ByteArray(2).also(input::readFully))

        val username = credentials.username.encodeToByteArray()
        val password = credentials.password.encodeToByteArray()
        output.writeByte(0x01)
        output.writeByte(username.size)
        output.write(username)
        output.writeByte(password.size)
        output.write(password)
        output.flush()
        assertArrayEquals(byteArrayOf(0x01, 0x00), ByteArray(2).also(input::readFully))
    }
}
