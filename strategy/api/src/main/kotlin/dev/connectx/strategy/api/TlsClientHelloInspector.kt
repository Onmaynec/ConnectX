package dev.connectx.strategy.api

internal object TlsClientHelloInspector {
    const val TLS_RECORD_HEADER_BYTES = 5
    const val HANDSHAKE_HEADER_BYTES = 4
    const val CLIENT_HELLO_FIXED_PREFIX_BYTES = 34
    const val MIN_CLIENT_HELLO_RECORD_BYTES =
        HANDSHAKE_HEADER_BYTES + CLIENT_HELLO_FIXED_PREFIX_BYTES + 1
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
            return Result.Invalid(StrategyRefusalReason.PAYLOAD_TOO_SMALL)
        }
        if (payload.size > MAX_LAB_PAYLOAD_BYTES) {
            return Result.Invalid(StrategyRefusalReason.PAYLOAD_TOO_LARGE)
        }
        if (payload.u8(0) != TLS_CONTENT_TYPE_HANDSHAKE) {
            return Result.Invalid(StrategyRefusalReason.NOT_TLS_HANDSHAKE)
        }

        val major = payload.u8(1)
        val minor = payload.u8(2)
        if (major != TLS_MAJOR_VERSION || minor !in TLS_MINOR_VERSION_RANGE) {
            return Result.Invalid(StrategyRefusalReason.NOT_TLS_HANDSHAKE)
        }

        val recordLength = payload.u16(3)
        val totalRecordBytes = TLS_RECORD_HEADER_BYTES + recordLength
        if (recordLength < MIN_CLIENT_HELLO_RECORD_BYTES) {
            return Result.Invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        if (totalRecordBytes > payload.size) {
            return Result.Invalid(StrategyRefusalReason.TRUNCATED_RECORD)
        }
        if (totalRecordBytes != payload.size) {
            return Result.Invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        if (payload.u8(TLS_RECORD_HEADER_BYTES) != HANDSHAKE_TYPE_CLIENT_HELLO) {
            return Result.Invalid(StrategyRefusalReason.NOT_CLIENT_HELLO)
        }

        val handshakeLength = payload.u24(TLS_RECORD_HEADER_BYTES + 1)
        if (handshakeLength != recordLength - HANDSHAKE_HEADER_BYTES) {
            return Result.Invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }
        if (handshakeLength < CLIENT_HELLO_FIXED_PREFIX_BYTES + 1) {
            return Result.Invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }

        val splitOffset = TLS_RECORD_HEADER_BYTES + HANDSHAKE_HEADER_BYTES +
            CLIENT_HELLO_FIXED_PREFIX_BYTES
        if (splitOffset !in 1 until payload.size) {
            return Result.Invalid(StrategyRefusalReason.MALFORMED_LENGTH)
        }

        return Result.Valid(
            ValidClientHello(
                recordLength = recordLength,
                handshakeLength = handshakeLength,
                splitOffset = splitOffset,
            ),
        )
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u16(offset: Int): Int =
        (u8(offset) shl 8) or u8(offset + 1)

    private fun ByteArray.u24(offset: Int): Int =
        (u8(offset) shl 16) or (u8(offset + 1) shl 8) or u8(offset + 2)

    private const val TLS_CONTENT_TYPE_HANDSHAKE = 0x16
    private const val TLS_MAJOR_VERSION = 0x03
    private val TLS_MINOR_VERSION_RANGE = 0x01..0x04
    private const val HANDSHAKE_TYPE_CLIENT_HELLO = 0x01
}
