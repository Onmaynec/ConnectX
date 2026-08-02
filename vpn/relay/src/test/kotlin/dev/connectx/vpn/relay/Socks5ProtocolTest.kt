package dev.connectx.vpn.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Socks5ProtocolTest {
    @Test
    fun `negotiation selects no-auth method`() {
        val input = DataInputStream(
            ByteArrayInputStream(
                byteArrayOf(0x05, 0x02, 0x02, 0x00),
            ),
        )
        val bytes = ByteArrayOutputStream()

        Socks5Protocol.negotiateAuthentication(
            input = input,
            output = DataOutputStream(bytes),
        )

        assertArrayEquals(
            byteArrayOf(0x05, 0x00),
            bytes.toByteArray(),
        )
    }

    @Test
    fun `connect request parses domain and port`() {
        val host = "example.com".encodeToByteArray()
        val requestBytes = buildList<Byte> {
            add(0x05)
            add(0x01)
            add(0x00)
            add(0x03)
            add(host.size.toByte())
            addAll(host.toList())
            add(0x01)
            add(0xBB.toByte())
        }.toByteArray()

        val request = Socks5Protocol.readConnectRequest(
            DataInputStream(ByteArrayInputStream(requestBytes)),
        )

        assertEquals("example.com", request.host)
        assertEquals(443, request.port)
    }

    @Test(expected = UnsupportedCommandException::class)
    fun `udp associate is rejected until UDP relay is implemented`() {
        val requestBytes = byteArrayOf(
            0x05,
            0x03,
            0x00,
            0x01,
            127,
            0,
            0,
            1,
            0x00,
            0x35,
        )

        Socks5Protocol.readConnectRequest(
            DataInputStream(ByteArrayInputStream(requestBytes)),
        )
    }
}
