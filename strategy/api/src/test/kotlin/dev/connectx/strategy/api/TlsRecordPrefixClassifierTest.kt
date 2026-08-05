package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Test

class TlsRecordPrefixClassifierTest {
    @Test
    fun acceptsHandshakeAndAlertHeadersWithoutReadingPayload() {
        assertEquals(
            TlsRecordPrefixResult.Accepted(
                kind = TlsRecordKind.HANDSHAKE,
                legacyMajor = 3,
                legacyMinor = 3,
                declaredRecordBytes = 122,
            ),
            TlsRecordPrefixClassifier.classify(
                byteArrayOf(22, 3, 3, 0, 122),
            ),
        )
        assertEquals(
            TlsRecordPrefixResult.Accepted(
                kind = TlsRecordKind.ALERT,
                legacyMajor = 3,
                legacyMinor = 3,
                declaredRecordBytes = 2,
            ),
            TlsRecordPrefixClassifier.classify(
                byteArrayOf(21, 3, 3, 0, 2, 99, 100),
                length = 5,
            ),
        )
    }

    @Test
    fun rejectsTruncatedOrInvalidHeaders() {
        val cases = listOf(
            byteArrayOf(22, 3, 3, 0) to TlsRecordPrefixRejection.TRUNCATED_HEADER,
            byteArrayOf(22, 2, 0, 0, 1) to TlsRecordPrefixRejection.INVALID_LEGACY_VERSION,
            byteArrayOf(22, 3, 0, 0, 1) to TlsRecordPrefixRejection.INVALID_LEGACY_VERSION,
            byteArrayOf(22, 3, 3, 0, 0) to TlsRecordPrefixRejection.INVALID_RECORD_LENGTH,
            byteArrayOf(23, 3, 3, 0, 1) to TlsRecordPrefixRejection.UNEXPECTED_CONTENT_TYPE,
        )

        cases.forEach { (prefix, expectedReason) ->
            assertEquals(
                TlsRecordPrefixResult.Rejected(expectedReason),
                TlsRecordPrefixClassifier.classify(prefix),
            )
        }
    }

    @Test
    fun rejectsRecordLengthAboveCiphertextBound() {
        val tooLarge = TlsRecordPrefixClassifier.MAX_CIPHERTEXT_RECORD_BYTES + 1
        val prefix = byteArrayOf(
            22,
            3,
            3,
            (tooLarge ushr 8).toByte(),
            tooLarge.toByte(),
        )

        assertEquals(
            TlsRecordPrefixResult.Rejected(
                TlsRecordPrefixRejection.INVALID_RECORD_LENGTH,
            ),
            TlsRecordPrefixClassifier.classify(prefix),
        )
    }

    @Test
    fun rejectsLengthArgumentOutsideProvidedBuffer() {
        assertEquals(
            TlsRecordPrefixResult.Rejected(
                TlsRecordPrefixRejection.TRUNCATED_HEADER,
            ),
            TlsRecordPrefixClassifier.classify(
                prefix = byteArrayOf(22, 3, 3, 0, 1),
                length = 6,
            ),
        )
    }
}
