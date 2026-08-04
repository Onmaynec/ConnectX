package dev.connectx.strategy.api

/**
 * Deterministic builder for the bounded TLS ClientHello used by local Lab probes.
 *
 * The record is structurally complete: TLS 1.2 legacy version, 32-byte random,
 * empty session id, one TLS 1.2 cipher suite and null compression. It carries no
 * host name, extension, credential or external destination.
 */
object LabTlsClientHello {
    const val RANDOM_BYTES = 32
    const val SPLIT_OFFSET = 43
    const val PAYLOAD_BYTES = 50

    fun create(randomBytes: ByteArray): ByteArray {
        require(randomBytes.size == RANDOM_BYTES) {
            "Lab ClientHello random must contain exactly $RANDOM_BYTES bytes"
        }

        val body = ByteArray(CLIENT_HELLO_BODY_BYTES)
        body[0] = TLS_MAJOR_VERSION.toByte()
        body[1] = TLS_1_2_MINOR_VERSION.toByte()
        randomBytes.copyInto(body, destinationOffset = CLIENT_RANDOM_OFFSET)
        body[SESSION_ID_LENGTH_OFFSET] = 0
        body[CIPHER_SUITES_LENGTH_OFFSET] = 0
        body[CIPHER_SUITES_LENGTH_OFFSET + 1] = CIPHER_SUITE_BYTES.toByte()
        body[CIPHER_SUITE_OFFSET] = TLS_RSA_WITH_AES_128_CBC_SHA_HIGH.toByte()
        body[CIPHER_SUITE_OFFSET + 1] = TLS_RSA_WITH_AES_128_CBC_SHA_LOW.toByte()
        body[COMPRESSION_METHODS_LENGTH_OFFSET] = 1
        body[COMPRESSION_METHOD_OFFSET] = 0

        val handshake = ByteArray(HANDSHAKE_HEADER_BYTES + body.size)
        handshake[0] = HANDSHAKE_TYPE_CLIENT_HELLO.toByte()
        handshake.writeU24(offset = 1, value = body.size)
        body.copyInto(handshake, destinationOffset = HANDSHAKE_HEADER_BYTES)

        return ByteArray(RECORD_HEADER_BYTES + handshake.size).also { record ->
            record[0] = TLS_CONTENT_TYPE_HANDSHAKE.toByte()
            record[1] = TLS_MAJOR_VERSION.toByte()
            record[2] = TLS_1_2_MINOR_VERSION.toByte()
            record.writeU16(offset = 3, value = handshake.size)
            handshake.copyInto(record, destinationOffset = RECORD_HEADER_BYTES)
            check(record.size == PAYLOAD_BYTES) {
                "Lab ClientHello size invariant changed"
            }
        }
    }

    private fun ByteArray.writeU16(offset: Int, value: Int) {
        this[offset] = ((value ushr 8) and 0xff).toByte()
        this[offset + 1] = (value and 0xff).toByte()
    }

    private fun ByteArray.writeU24(offset: Int, value: Int) {
        this[offset] = ((value ushr 16) and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
        this[offset + 2] = (value and 0xff).toByte()
    }

    private const val RECORD_HEADER_BYTES = 5
    private const val HANDSHAKE_HEADER_BYTES = 4
    private const val CLIENT_HELLO_BODY_BYTES = 41
    private const val CLIENT_RANDOM_OFFSET = 2
    private const val SESSION_ID_LENGTH_OFFSET = 34
    private const val CIPHER_SUITES_LENGTH_OFFSET = 35
    private const val CIPHER_SUITE_OFFSET = 37
    private const val COMPRESSION_METHODS_LENGTH_OFFSET = 39
    private const val COMPRESSION_METHOD_OFFSET = 40
    private const val CIPHER_SUITE_BYTES = 2

    private const val TLS_CONTENT_TYPE_HANDSHAKE = 0x16
    private const val TLS_MAJOR_VERSION = 0x03
    private const val TLS_1_2_MINOR_VERSION = 0x03
    private const val HANDSHAKE_TYPE_CLIENT_HELLO = 0x01
    private const val TLS_RSA_WITH_AES_128_CBC_SHA_HIGH = 0x00
    private const val TLS_RSA_WITH_AES_128_CBC_SHA_LOW = 0x2f
}
