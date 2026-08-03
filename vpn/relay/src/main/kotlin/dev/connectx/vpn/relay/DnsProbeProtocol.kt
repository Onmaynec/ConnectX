package dev.connectx.vpn.relay

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Minimal bounded DNS codec used only by the deterministic alpha DNS probe.
 *
 * It intentionally supports exactly one uncompressed A/IN question for
 * [PROBE_NAME] and exactly one A answer. It is not a general DNS resolver.
 */
object DnsProbeProtocol {
    const val PROBE_NAME: String = "connectx.invalid"
    const val PROBE_ANSWER: String = "192.0.2.42"
    const val MAX_PACKET_BYTES: Int = 512

    private const val HEADER_BYTES = 12
    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val QUERY_FLAGS = 0x0100
    // QR + AA + copied RD. RA stays clear because this responder never recurses.
    private const val RESPONSE_FLAGS = 0x8500
    private const val RESPONSE_TTL_SECONDS = 60L
    private const val QUESTION_OFFSET = HEADER_BYTES
    private const val ANSWER_NAME_POINTER = 0xC00C

    data class Query(
        val transactionId: Int,
        val questionEndOffset: Int,
    )

    data class Response(
        val transactionId: Int,
        val address: Inet4Address,
    )

    fun buildQuery(transactionId: Int): ByteArray {
        require(transactionId in 0..0xFFFF)
        val output = ByteWriter(MAX_PACKET_BYTES)
        output.writeU16(transactionId)
        output.writeU16(QUERY_FLAGS)
        output.writeU16(1)
        output.writeU16(0)
        output.writeU16(0)
        output.writeU16(0)
        output.writeName(PROBE_NAME)
        output.writeU16(TYPE_A)
        output.writeU16(CLASS_IN)
        return output.toByteArray()
    }

    fun parseQuery(packet: ByteArray, length: Int = packet.size): Query {
        requirePacketLength(packet, length)
        val reader = ByteReader(packet, length)
        val transactionId = reader.readU16()
        require(reader.readU16() == QUERY_FLAGS) { "Unexpected DNS query flags" }
        require(reader.readU16() == 1) { "DNS probe requires exactly one question" }
        require(reader.readU16() == 0) { "DNS probe query must not contain answers" }
        require(reader.readU16() == 0) { "DNS probe query must not contain authorities" }
        require(reader.readU16() == 0) { "DNS probe query must not contain additional records" }
        require(reader.readName() == PROBE_NAME) { "Unexpected DNS probe name" }
        require(reader.readU16() == TYPE_A) { "DNS probe accepts only A queries" }
        require(reader.readU16() == CLASS_IN) { "DNS probe accepts only IN class" }
        require(reader.position == length) { "Trailing bytes in DNS probe query" }
        return Query(transactionId, reader.position)
    }

    fun buildResponse(queryPacket: ByteArray, length: Int = queryPacket.size): ByteArray {
        val query = parseQuery(queryPacket, length)
        val answer = InetAddress.getByName(PROBE_ANSWER) as Inet4Address
        val output = ByteWriter(MAX_PACKET_BYTES)
        output.writeU16(query.transactionId)
        output.writeU16(RESPONSE_FLAGS)
        output.writeU16(1)
        output.writeU16(1)
        output.writeU16(0)
        output.writeU16(0)
        output.writeBytes(queryPacket, QUESTION_OFFSET, query.questionEndOffset - QUESTION_OFFSET)
        output.writeU16(ANSWER_NAME_POINTER)
        output.writeU16(TYPE_A)
        output.writeU16(CLASS_IN)
        output.writeU32(RESPONSE_TTL_SECONDS)
        output.writeU16(4)
        output.writeBytes(answer.address)
        return output.toByteArray()
    }

    fun parseResponse(
        packet: ByteArray,
        expectedTransactionId: Int,
        length: Int = packet.size,
    ): Response {
        require(expectedTransactionId in 0..0xFFFF)
        requirePacketLength(packet, length)
        val reader = ByteReader(packet, length)
        val transactionId = reader.readU16()
        require(transactionId == expectedTransactionId) { "DNS transaction ID mismatch" }
        require(reader.readU16() == RESPONSE_FLAGS) { "Unexpected DNS response flags" }
        require(reader.readU16() == 1) { "DNS response must contain one question" }
        require(reader.readU16() == 1) { "DNS response must contain one answer" }
        require(reader.readU16() == 0) { "DNS response must not contain authorities" }
        require(reader.readU16() == 0) { "DNS response must not contain additional records" }
        require(reader.readName() == PROBE_NAME) { "Unexpected DNS response name" }
        require(reader.readU16() == TYPE_A) { "Unexpected DNS response question type" }
        require(reader.readU16() == CLASS_IN) { "Unexpected DNS response question class" }
        require(reader.readU16() == ANSWER_NAME_POINTER) { "Unexpected DNS answer name" }
        require(reader.readU16() == TYPE_A) { "Unexpected DNS answer type" }
        require(reader.readU16() == CLASS_IN) { "Unexpected DNS answer class" }
        require(reader.readU32() == RESPONSE_TTL_SECONDS) { "Unexpected DNS answer TTL" }
        require(reader.readU16() == 4) { "Unexpected DNS A record length" }
        val address = InetAddress.getByAddress(reader.readBytes(4)) as Inet4Address
        require(address.hostAddress == PROBE_ANSWER) { "Unexpected DNS probe answer" }
        require(reader.position == length) { "Trailing bytes in DNS probe response" }
        return Response(transactionId, address)
    }

    private fun requirePacketLength(packet: ByteArray, length: Int) {
        require(length in HEADER_BYTES..minOf(packet.size, MAX_PACKET_BYTES)) {
            "DNS probe packet length is outside the bounded range"
        }
    }

    private class ByteReader(
        private val bytes: ByteArray,
        private val limit: Int,
    ) {
        var position: Int = 0
            private set

        fun readU16(): Int {
            requireRemaining(2)
            val value = ((bytes[position].toInt() and 0xFF) shl 8) or
                (bytes[position + 1].toInt() and 0xFF)
            position += 2
            return value
        }

        fun readU32(): Long {
            requireRemaining(4)
            val value = ((bytes[position].toLong() and 0xFF) shl 24) or
                ((bytes[position + 1].toLong() and 0xFF) shl 16) or
                ((bytes[position + 2].toLong() and 0xFF) shl 8) or
                (bytes[position + 3].toLong() and 0xFF)
            position += 4
            return value
        }

        fun readBytes(count: Int): ByteArray {
            require(count >= 0)
            requireRemaining(count)
            return bytes.copyOfRange(position, position + count).also {
                position += count
            }
        }

        fun readName(): String {
            val labels = mutableListOf<String>()
            var encodedBytes = 0
            while (true) {
                requireRemaining(1)
                val labelLength = bytes[position++].toInt() and 0xFF
                encodedBytes += 1
                require(labelLength and 0xC0 == 0) { "Compressed DNS names are not accepted" }
                if (labelLength == 0) break
                require(labelLength in 1..63) { "Invalid DNS label length" }
                requireRemaining(labelLength)
                val label = bytes.copyOfRange(position, position + labelLength)
                    .toString(Charsets.US_ASCII)
                require(label.all { it.isLetterOrDigit() || it == '-' }) {
                    "Invalid character in DNS label"
                }
                require(label.first() != '-' && label.last() != '-') {
                    "DNS labels cannot start or end with a hyphen"
                }
                labels += label.lowercase()
                position += labelLength
                encodedBytes += labelLength
                require(encodedBytes <= 255) { "DNS name exceeds protocol limit" }
            }
            require(labels.isNotEmpty()) { "Root DNS name is not accepted" }
            return labels.joinToString(".")
        }

        private fun requireRemaining(count: Int) {
            require(position + count <= limit) { "Truncated DNS probe packet" }
        }
    }

    private class ByteWriter(private val maxBytes: Int) {
        private val bytes = ArrayList<Byte>()

        fun writeU16(value: Int) {
            require(value in 0..0xFFFF)
            append((value ushr 8).toByte())
            append(value.toByte())
        }

        fun writeU32(value: Long) {
            require(value in 0..0xFFFF_FFFFL)
            append((value ushr 24).toByte())
            append((value ushr 16).toByte())
            append((value ushr 8).toByte())
            append(value.toByte())
        }

        fun writeName(name: String) {
            val labels = name.split('.')
            require(labels.isNotEmpty())
            labels.forEach { label ->
                val encoded = label.encodeToByteArray()
                require(encoded.size in 1..63)
                append(encoded.size.toByte())
                writeBytes(encoded)
            }
            append(0.toByte())
        }

        fun writeBytes(source: ByteArray, offset: Int = 0, count: Int = source.size) {
            require(offset >= 0 && count >= 0 && offset + count <= source.size)
            repeat(count) { index -> append(source[offset + index]) }
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }

        private fun append(value: Byte) {
            require(bytes.size < maxBytes) { "DNS probe packet exceeds $maxBytes bytes" }
            bytes += value
        }
    }
}
