package dev.connectx.vpn.relay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProbeProtocolTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `bounded query and deterministic response round trip`() {
        val transactionId = 0xA51D
        val query = DnsProbeProtocol.buildQuery(transactionId)
        val parsedQuery = DnsProbeProtocol.parseQuery(query)
        val response = DnsProbeProtocol.buildResponse(query)
        val parsedResponse = DnsProbeProtocol.parseResponse(response, transactionId)

        assertEquals(transactionId, parsedQuery.transactionId)
        assertEquals(transactionId, parsedResponse.transactionId)
        assertEquals(DnsProbeProtocol.PROBE_ANSWER, parsedResponse.address.hostAddress)
        assertTrue(query.size <= DnsProbeProtocol.MAX_PACKET_BYTES)
        assertTrue(response.size <= DnsProbeProtocol.MAX_PACKET_BYTES)
    }

    @Test
    fun `query parser rejects compression multiple questions and trailing bytes`() {
        val compressed = DnsProbeProtocol.buildQuery(1).also { packet ->
            packet[12] = 0xC0.toByte()
            packet[13] = 0x0C
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsProbeProtocol.parseQuery(compressed)
        }

        val multipleQuestions = DnsProbeProtocol.buildQuery(2).also { packet ->
            packet[4] = 0
            packet[5] = 2
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsProbeProtocol.parseQuery(multipleQuestions)
        }

        val trailing = DnsProbeProtocol.buildQuery(3) + byteArrayOf(0)
        assertThrows(IllegalArgumentException::class.java) {
            DnsProbeProtocol.parseQuery(trailing)
        }
    }

    @Test
    fun `response parser rejects transaction mismatch and altered answer`() {
        val query = DnsProbeProtocol.buildQuery(0x1234)
        val response = DnsProbeProtocol.buildResponse(query)

        assertThrows(IllegalArgumentException::class.java) {
            DnsProbeProtocol.parseResponse(response, 0x4321)
        }

        val altered = response.copyOf().also { packet ->
            packet[packet.lastIndex] = 43
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsProbeProtocol.parseResponse(altered, 0x1234)
        }
    }

    @Test
    fun `loopback responder never forwards and answers only exact probe query`() {
        val server = LoopbackDnsProbeServer()
        val port = server.start()

        try {
            DatagramSocket(null).use { client ->
                client.bind(InetSocketAddress(loopback, 0))
                client.soTimeout = 2_000
                val transactionId = 0xD065
                val query = DnsProbeProtocol.buildQuery(transactionId)
                client.send(
                    DatagramPacket(
                        query,
                        query.size,
                        InetSocketAddress(loopback, port),
                    ),
                )

                val responseBytes = ByteArray(DnsProbeProtocol.MAX_PACKET_BYTES)
                val response = DatagramPacket(responseBytes, responseBytes.size)
                client.receive(response)
                val parsed = DnsProbeProtocol.parseResponse(
                    packet = response.data,
                    expectedTransactionId = transactionId,
                    length = response.length,
                )
                assertEquals(DnsProbeProtocol.PROBE_ANSWER, parsed.address.hostAddress)
            }

            val deadline = System.nanoTime() + 1_000_000_000L
            var stats = server.stats()
            while (stats.responses < 1L && System.nanoTime() < deadline) {
                Thread.sleep(10)
                stats = server.stats()
            }
            assertEquals(port, stats.listeningPort)
            assertEquals(1L, stats.queries)
            assertEquals(1L, stats.responses)
            assertEquals(0L, stats.rejected)
        } finally {
            server.close()
        }

        assertEquals(0, server.port())
    }
}
