package dev.connectx.strategy.api

internal object TlsClientHelloInspector {
    const val TLS_RECORD_HEADER_BYTES = 5
    const val HANDSHAKE_HEADER_BYTES = 4
    const val CLIENT_HELLO_FIXED_PREFIX_BYTES = 34
    const val MIN_CLIENT_HELLO_BODY_BYTES = 41
    const val MIN_CLIENT_HELLO_RECORD_BYTES =
        HANDSHAKE_HEADER_BYTES + MIN_CLIENT_HELLO_BODY_BYTES
    const val MAX_LAB_PAYLOAD_BYTES = 16 * 1024

    data class ValidClientHello(
        val recordLength: Int,
        val handshakeLength: Int,
        val splitOffset: Int,
    )

    sealed interface Result {
        data class Valid(val metadata: ValidClientHello) : Result
        data class Invalid(val reason: StrategyRefusalReason) : Result
    }

    fun inspect(payload: ByteArray): Result {
        if (payload.size < TLS_RECORD_HEADER_BYTES + HANDSHAKE_HEADER_BYTES) {
            return invalid(StrategyRefusalReason.PAYLOAD_TOO_SMALL)
        }
        if (payload.size > MAX_LAB_PAYLOAD_BYTES) {
            return invalid(StrategyRefusalReason.PAYLOAD_TOO_LARGE)
        }
        if (payload.u8(0) != TLS_CONTENT_TYPE_HANDSHAKE) {
            return invalid(StrategyRefusalReason.NOT_TLS_HANDSHAKE)
        }

        val recordMajor = payload.u8(1)
        val recordMinor = payload.u8(2)
        if (recordMajor != TLS_MAJOR_VERSION || recordMinor !in TLS_MINOR_VERSION_RANGE) {
            return invalid(StrategyRefusalReason.NOT_TLS_HANDSHAKE)
        }

        val recordLength = payload.u16(3)
        val totalRecordBytes = TLS_RECORD_HEADER_BYTES + recordLength
        if (recordLength < MIN_CLIENT_HELLO_RECORD_BYTES) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        if (totalRecordBytes > payload.size) {
            return invalid(StrategyRefusalReason.TRUNCATED_RECORD)
        }
        if (totalRecordBytes != payload.size) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        if (payload.u8(TLS_RECORD_HEADER_BYTES) != HANDSHAKE_TYPE_CLIENT_HELLO) {
            return invalid(StrategyRefusalReason.NOT_CLIENT_HELLO)
        }

        val handshakeLength = payload.u24(TLS_RECORD_HEADER_BYTES + 1)
        if (handshakeLength != recordLength - HANDSHAKE_HEADER_BYTES) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        if (handshakeLength < MIN_CLIENT_HELLO_BODY_BYTES) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }

        val bodyStart = TLS_RECORD_HEADER_BYTES + HANDSHAKE_HEADER_BYTES
        val bodyEnd = bodyStart + handshakeLength
        if (bodyEnd != payload.size) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        if (
            payload.u8(bodyStart) != TLS_MAJOR_VERSION ||
            payload.u8(bodyStart + 1) !in TLS_MINOR_VERSION_RANGE
        ) {
            return invalid(StrategyRefusalReason.NOT_CLIENT_HELLO)
        }

        var cursor = bodyStart + CLIENT_HELLO_FIXED_PREFIX_BYTES
        val splitOffset = cursor

        val sessionIdLength = payload.u8(cursor)
        cursor += SESSION_ID_LENGTH_BYTES
        if (sessionIdLength > MAX_SESSION_ID_BYTES || !hasBytes(cursor, sessionIdLength, bodyEnd)) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        cursor += sessionIdLength

        if (!hasBytes(cursor, CIPHER_SUITES_LENGTH_BYTES, bodyEnd)) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        val cipherSuitesLength = payload.u16(cursor)
        cursor += CIPHER_SUITES_LENGTH_BYTES
        if (
            cipherSuitesLength < MIN_CIPHER_SUITES_BYTES ||
            cipherSuitesLength % CIPHER_SUITE_BYTES != 0 ||
            !hasBytes(cursor, cipherSuitesLength, bodyEnd)
        ) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        cursor += cipherSuitesLength

        if (!hasBytes(cursor, COMPRESSION_METHODS_LENGTH_BYTES, bodyEnd)) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        val compressionMethodsLength = payload.u8(cursor)
        cursor += COMPRESSION_METHODS_LENGTH_BYTES
        if (
            compressionMethodsLength < MIN_COMPRESSION_METHODS_BYTES ||
            !hasBytes(cursor, compressionMethodsLength, bodyEnd)
        ) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        cursor += compressionMethodsLength

        if (cursor < bodyEnd) {
            if (!hasBytes(cursor, EXTENSIONS_LENGTH_BYTES, bodyEnd)) {
                return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
            }
            val extensionsLength = payload.u16(cursor)
            cursor += EXTENSIONS_LENGTH_BYTES
            if (!hasBytes(cursor, extensionsLength, bodyEnd) || cursor + extensionsLength != bodyEnd) {
                return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
            }

            val extensionsEnd = cursor + extensionsLength
            while (cursor < extensionsEnd) {
                if (!hasBytes(cursor, EXTENSION_HEADER_BYTES, extensionsEnd)) {
                    return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
                }
                val extensionLength = payload.u16(cursor + EXTENSION_TYPE_BYTES)
                cursor += EXTENSION_HEADER_BYTES
                if (!hasBytes(cursor, extensionLength, extensionsEnd)) {
                    return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
                }
                cursor += extensionLength
            }
        }

        if (cursor != bodyEnd || splitOffset !in 1 until payload.size) {
            return invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }

        return Result.Valid(
            ValidClientHello(
                recordLength = recordLength,
                handshakeLength = handshakeLength,
                splitOffset = splitOffset,
            ),
        )
    }

    private fun invalid(reason: StrategyRefusalReason): Result.Invalid =
        Result.Invalid(reason)

    private fun hasBytes(offset: Int, length: Int, endExclusive: Int): Boolean =
        offset >= 0 && length >= 0 && offset <= endExclusive - length

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u16(offset: Int): Int =
        (u8(offset) shl 8) or u8(offset + 1)

    private fun ByteArray.u24(offset: Int): Int =
        (u8(offset) shl 16) or (u8(offset + 1) shl 8) or u8(offset + 2)

    private const val TLS_CONTENT_TYPE_HANDSHAKE = 0x16
    private const val TLS_MAJOR_VERSION = 0x03
    private val TLS_MINOR_VERSION_RANGE = 0x01..0x04
    private const val HANDSHAKE_TYPE_CLIENT_HELLO = 0x01

    private const val SESSION_ID_LENGTH_BYTES = 1
    private const val MAX_SESSION_ID_BYTES = 32
    private const val CIPHER_SUITES_LENGTH_BYTES = 2
    private const val CIPHER_SUITE_BYTES = 2
    private const val MIN_CIPHER_SUITES_BYTES = 2
    private const val COMPRESSION_METHODS_LENGTH_BYTES = 1
    private const val MIN_COMPRESSION_METHODS_BYTES = 1
    private const val EXTENSIONS_LENGTH_BYTES = 2
    private const val EXTENSION_TYPE_BYTES = 2
    private const val EXTENSION_HEADER_BYTES = 4
}
