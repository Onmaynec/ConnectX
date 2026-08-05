package dev.connectx.strategy.api

/**
 * Classifies only the five-byte TLS record header returned by an evidence target.
 * No TLS payload is parsed or retained.
 */
object TlsRecordPrefixClassifier {
    const val HEADER_BYTES: Int = 5
    const val MAX_CIPHERTEXT_RECORD_BYTES: Int = 18_432

    fun classify(prefix: ByteArray, length: Int = prefix.size): TlsRecordPrefixResult {
        if (length < HEADER_BYTES || length > prefix.size) {
            return TlsRecordPrefixResult.Rejected(TlsRecordPrefixRejection.TRUNCATED_HEADER)
        }

        val contentType = unsigned(prefix[0])
        val major = unsigned(prefix[1])
        val minor = unsigned(prefix[2])
        val recordLength = (unsigned(prefix[3]) shl 8) or unsigned(prefix[4])

        if (major != 3 || minor !in 1..4) {
            return TlsRecordPrefixResult.Rejected(TlsRecordPrefixRejection.INVALID_LEGACY_VERSION)
        }
        if (recordLength !in 1..MAX_CIPHERTEXT_RECORD_BYTES) {
            return TlsRecordPrefixResult.Rejected(TlsRecordPrefixRejection.INVALID_RECORD_LENGTH)
        }

        val kind = when (contentType) {
            21 -> TlsRecordKind.ALERT
            22 -> TlsRecordKind.HANDSHAKE
            else -> return TlsRecordPrefixResult.Rejected(
                TlsRecordPrefixRejection.UNEXPECTED_CONTENT_TYPE,
            )
        }

        return TlsRecordPrefixResult.Accepted(
            kind = kind,
            legacyMajor = major,
            legacyMinor = minor,
            declaredRecordBytes = recordLength,
        )
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff
}

enum class TlsRecordKind {
    HANDSHAKE,
    ALERT,
}

sealed interface TlsRecordPrefixResult {
    data class Accepted(
        val kind: TlsRecordKind,
        val legacyMajor: Int,
        val legacyMinor: Int,
        val declaredRecordBytes: Int,
    ) : TlsRecordPrefixResult

    data class Rejected(
        val reason: TlsRecordPrefixRejection,
    ) : TlsRecordPrefixResult
}

enum class TlsRecordPrefixRejection {
    TRUNCATED_HEADER,
    INVALID_LEGACY_VERSION,
    INVALID_RECORD_LENGTH,
    UNEXPECTED_CONTENT_TYPE,
}
