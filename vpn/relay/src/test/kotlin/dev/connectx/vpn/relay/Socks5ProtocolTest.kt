package dev.connectx.vpn.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class Socks5ProtocolTest {
    private val credentials = Socks5Credentials(
        username = "connectx",
        password = "local-test-secret",
    )

    @Test
    fun `negotiation requires username password and accepts valid credentials`() {
        val input = DataInputStream(
            ByteArrayInputStream(
                authenticationBytes(
                    username = credentials.username,
                    password = credentials.password,
                ),
            ),
        )
        val bytes = ByteArrayOutputStream()

        Socks5Protocol.authenticateClient(
            input = input,
            output = DataOutputStream(bytes),
            credentials = credentials,
        )

        assertArrayEquals(
            byteArrayOf(0x05, 0x02, 0x01, 0x00),
            bytes.toByteArray(),
        )
    }

    @Test
    fun `invalid password is rejected`() {
        val input = DataInputStream(
            ByteArrayInputStream(
                authenticationBytes(
                    username = credentials.username,
                    password = "wrong-secret",
                ),
            ),
        )
        val bytes = ByteArrayOutputStream()

        try {
            Socks5Protocol.authenticateClient(
                input = input,
                output = DataOutputStream(bytes),
                credentials = credentials,
            )
            fail("Expected invalid SOCKS5 credentials to be rejected")
        } catch (_: IOException) {
            // Expected.
        }

        assertArrayEquals(
            byteArrayOf(0x05, 0x02, 0x01, 0x01),
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

    private fun authenticationBytes(
        username: String,
        password: String,
    ): ByteArray {
        val usernameBytes = username.encodeToByteArray()
        val passwordBytes = password.encodeToByteArray()
        return buildList<Byte> {
            add(0x05)
            add(0x01)
            add(0x02)
            add(0x01)
            add(usernameBytes.size.toByte())
            addAll(usernameBytes.toList())
            add(passwordBytes.size.toByte())
            addAll(passwordBytes.toList())
        }.toByteArray()
    }
}
