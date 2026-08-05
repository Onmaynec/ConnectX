package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackTlsEvidenceServerTest {
    @Test
    fun acceptsBoundedClientHelloAndReturnsFixedTlsAlert() {
        LoopbackTlsEvidenceServer().use { server ->
            val port = server.start()
            val socket = Socket()
            socket.use {
                socket.soTimeout = 2_000
                socket.connect(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                    2_000,
                )
                socket.getOutputStream().apply {
                    write(syntheticClientHello())
                    flush()
                }

                val response = ByteArray(7)
                DataInputStream(socket.getInputStream()).readFully(response)
                assertArrayEquals(
                    byteArrayOf(21, 3, 3, 0, 2, 2, 40),
                    response,
                )
            }

            val stats = awaitStats(server, responses = 1L)
            assertEquals(1L, stats.accepted)
            assertEquals(1L, stats.responses)
            assertEquals(0L, stats.rejected)
        }
    }

    @Test
    fun rejectsNonHandshakeRecordWithoutResponding() {
        LoopbackTlsEvidenceServer(socketTimeoutMillis = 500).use { server ->
            val port = server.start()
            val socket = Socket()
            socket.use {
                socket.soTimeout = 1_000
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
                socket.getOutputStream().apply {
                    write(byteArrayOf(23, 3, 3, 0, 1, 0))
                    flush()
                }
                assertEquals(-1, socket.getInputStream().read())
            }

            val stats = awaitStats(server, rejected = 1L)
            assertEquals(1L, stats.accepted)
            assertEquals(0L, stats.responses)
            assertEquals(1L, stats.rejected)
        }
    }

    @Test
    fun rejectsClientHelloWhoseHandshakeLengthDoesNotMatchRecord() {
        LoopbackTlsEvidenceServer(socketTimeoutMillis = 500).use { server ->
            val port = server.start()
            val malformed = syntheticClientHello().also { record ->
                // Record contains 35 body bytes, but the handshake declares 34.
                record[8] = 34
            }
            Socket().use { socket ->
                socket.soTimeout = 1_000
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
                socket.getOutputStream().apply {
                    write(malformed)
                    flush()
                }
                assertEquals(-1, socket.getInputStream().read())
            }

            val stats = awaitStats(server, rejected = 1L)
            assertEquals(1L, stats.accepted)
            assertEquals(0L, stats.responses)
            assertEquals(1L, stats.rejected)
        }
    }

    @Test
    fun repeatedStopIsIdempotent() {
        val server = LoopbackTlsEvidenceServer()
        assertTrue(server.start() in 1..65535)
        server.stop()
        server.stop()
        assertEquals(0, server.stats().listeningPort)
    }

    private fun syntheticClientHello(): ByteArray {
        val body = ByteArray(35)
        body[0] = 3
        body[1] = 3
        val handshake = ByteArray(4 + body.size)
        handshake[0] = 1
        handshake[1] = 0
        handshake[2] = 0
        handshake[3] = body.size.toByte()
        body.copyInto(handshake, destinationOffset = 4)

        return ByteArray(5 + handshake.size).also { record ->
            record[0] = 22
            record[1] = 3
            record[2] = 3
            record[3] = 0
            record[4] = handshake.size.toByte()
            handshake.copyInto(record, destinationOffset = 5)
        }
    }

    private fun awaitStats(
        server: LoopbackTlsEvidenceServer,
        responses: Long = 0L,
        rejected: Long = 0L,
    ): LoopbackTlsEvidenceStats {
        val deadline = System.nanoTime() + 1_000_000_000L
        var stats = server.stats()
        while (
            (stats.responses < responses || stats.rejected < rejected) &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(10)
            stats = server.stats()
        }
        return stats
    }
}
