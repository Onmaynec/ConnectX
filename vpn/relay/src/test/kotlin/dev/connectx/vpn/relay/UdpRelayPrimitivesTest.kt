package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpRelayPrimitivesTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `exact UDP resolver rejects every non diagnostic target`() {
        val resolver = ExactUdpRelayTargetOverride(
            source = RelayTarget("192.0.2.1", 18_081),
            destination = RelayTarget("127.0.0.1", 32_002),
        )

        assertEquals(
            RelayTarget("127.0.0.1", 32_002),
            resolver.resolve("192.0.2.1", 18_081),
        )
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve("192.0.2.1", 53)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve("198.51.100.1", 18_081)
        }
    }

    @Test
    fun `SOCKS5 UDP framing round trips nonce and rejects fragments`() {
        val target = RelayTarget("192.0.2.1", 18_081)
        val payload = "connectx-alpha5-udp".encodeToByteArray()
        val encoded = Socks5Protocol.encodeUdpDatagram(target, payload)
        val decoded = Socks5Protocol.decodeUdpDatagram(encoded)

        assertEquals(target, decoded.target)
        assertArrayEquals(payload, decoded.payload)

        encoded[2] = 1
        assertThrows(UnsupportedUdpFragmentException::class.java) {
            Socks5Protocol.decodeUdpDatagram(encoded)
        }
    }

    @Test
    fun `authenticated UDP association applies exact override and echoes datagram`() {
        val credentials = Socks5Credentials("connectx", "alpha5-udp-test")
        val echoServer = LoopbackUdpEchoServer(maxPayloadBytes = 512)
        val echoPort = echoServer.start()
        val relay = DirectTcpRelay(
            socketProtector = SocketProtector { true },
            credentials = credentials,
            datagramSocketProtector = DatagramSocketProtector { true },
            udpTargetResolver = ExactUdpRelayTargetOverride(
                source = RelayTarget("192.0.2.1", 18_081),
                destination = RelayTarget("127.0.0.1", echoPort),
            ),
        )

        try {
            Socket(loopback, relay.start()).use { control ->
                control.soTimeout = 2_000
                val input = DataInputStream(control.getInputStream())
                val output = DataOutputStream(control.getOutputStream())
                authenticate(input, output, credentials)

                output.write(
                    byteArrayOf(
                        0x05,
                        0x03,
                        0x00,
                        0x01,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                    ),
                )
                output.flush()

                val reply = ByteArray(10)
                input.readFully(reply)
                assertEquals(0, reply[1].toInt() and 0xFF)
                assertArrayEquals(
                    byteArrayOf(127, 0, 0, 1),
                    reply.copyOfRange(4, 8),
                )
                val associationPort =
                    ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF)
                assertTrue(associationPort in 1..65535)

                DatagramSocket(null).use { udpClient ->
                    udpClient.bind(InetSocketAddress(loopback, 0))
                    udpClient.soTimeout = 2_000
                    val logicalTarget = RelayTarget("192.0.2.1", 18_081)
                    val payload = ByteArray(64) { index -> index.toByte() }
                    val framed = Socks5Protocol.encodeUdpDatagram(logicalTarget, payload)
                    udpClient.send(
                        DatagramPacket(
                            framed,
                            framed.size,
                            InetSocketAddress(loopback, associationPort),
                        ),
                    )

                    val responseBytes = ByteArray(1_024)
                    val response = DatagramPacket(responseBytes, responseBytes.size)
                    udpClient.receive(response)
                    val decoded = Socks5Protocol.decodeUdpDatagram(
                        response.data,
                        response.length,
                    )
                    assertEquals(logicalTarget, decoded.target)
                    assertArrayEquals(payload, decoded.payload)
                }

                val deadline = System.nanoTime() + 1_000_000_000L
                var stats = relay.stats()
                while (stats.udpDatagrams < 1L && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                    stats = relay.stats()
                }
                assertTrue(stats.udpAssociations >= 1L)
                assertTrue(stats.udpDatagrams >= 1L)
                assertTrue(stats.udpUploadedBytes >= 64L)
                assertTrue(stats.udpDownloadedBytes >= 64L)
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
