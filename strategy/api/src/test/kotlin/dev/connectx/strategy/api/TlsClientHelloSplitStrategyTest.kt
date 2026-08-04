package dev.connectx.strategy.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsClientHelloSplitStrategyTest {
    private val strategy = TlsClientHelloSplitStrategy()
    private val labContext = StrategyContext(
        transport = TransportProtocol.TCP,
        network = NetworkProtocol.IPV4,
        application = ApplicationProtocol.TLS,
        scope = StrategyScope.LAB_ONLY,
    )
    private val enabledGate = StrategyFeatureGate(
        globallyEnabled = true,
        enabledStrategies = setOf(TlsClientHelloSplitStrategy.ID),
    )

    @Test
    fun featureGateIsDisabledByDefault() {
        val plan = strategy.plan(
            payload = validClientHello(),
            context = labContext,
            featureGate = StrategyFeatureGate(),
        )

        assertRefused(plan, StrategyRefusalReason.FEATURE_DISABLED)
    }

    @Test
    fun validClientHelloProducesTwoOrderedLosslessSegments() {
        val payload = validClientHello()
        val expected = payload.copyOf()
        val plan = strategy.plan(payload, labContext, enabledGate)

        assertTrue(plan is StrategyPlan.Segmented)
        plan as StrategyPlan.Segmented
        assertEquals(LabTlsClientHello.SPLIT_OFFSET, plan.splitOffset)
        assertEquals(2, plan.segments.size)
        assertEquals(LabTlsClientHello.SPLIT_OFFSET, plan.segments[0].size)
        assertEquals(
            LabTlsClientHello.PAYLOAD_BYTES - LabTlsClientHello.SPLIT_OFFSET,
            plan.segments[1].size,
        )
        assertArrayEquals(expected, plan.reconstruct())

        payload.fill(0)
        assertArrayEquals(expected, plan.reconstruct())

        val exposedSegments = plan.segments
        exposedSegments.forEach { it.fill(0) }
        assertArrayEquals(expected, plan.reconstruct())
        assertFalse(plan.segments.flattenBytes().all { it == 0.toByte() })
    }

    @Test
    fun userTrafficIsRejectedEvenWhenGlobalGateAllowsUserTraffic() {
        val context = labContext.copy(scope = StrategyScope.USER_TRAFFIC)
        val gate = enabledGate.copy(allowUserTraffic = true)

        assertRefused(
            strategy.plan(validClientHello(), context, gate),
            StrategyRefusalReason.OUTSIDE_ALLOWED_SCOPE,
        )
    }

    @Test
    fun alreadyPlannedPayloadIsRejected() {
        assertRefused(
            strategy.plan(
                validClientHello(),
                labContext.copy(alreadyPlanned = true),
                enabledGate,
            ),
            StrategyRefusalReason.ALREADY_PLANNED,
        )
    }

    @Test
    fun unsupportedTransportIsRejected() {
        assertRefused(
            strategy.plan(
                validClientHello(),
                labContext.copy(transport = TransportProtocol.UDP),
                enabledGate,
            ),
            StrategyRefusalReason.UNSUPPORTED_CONTEXT,
        )
    }

    @Test
    fun nonHandshakeTlsRecordIsRejected() {
        val payload = validClientHello().apply { this[0] = 0x17 }

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.NOT_TLS_HANDSHAKE,
        )
    }

    @Test
    fun nonClientHelloHandshakeIsRejected() {
        val payload = validClientHello().apply { this[5] = 0x02 }

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.NOT_CLIENT_HELLO,
        )
    }

    @Test
    fun tls11LegacyVersionRemainsStructurallyAccepted() {
        val payload = validClientHello().apply { this[9] = 0x02 }

        assertTrue(strategy.plan(payload, labContext, enabledGate) is StrategyPlan.Segmented)
    }

    @Test
    fun tls13VersionCodeIsRejectedAsClientHelloLegacyVersion() {
        val payload = validClientHello().apply { this[9] = 0x04 }

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.NOT_CLIENT_HELLO,
        )
    }

    @Test
    fun truncatedRecordIsRejectedBeforeReadingPastBuffer() {
        val payload = validClientHello().copyOf(20)

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.TRUNCATED_RECORD,
        )
    }

    @Test
    fun legacyPrefixOnlyFixtureIsNoLongerAcceptedAsClientHello() {
        assertRefused(
            strategy.plan(legacyPrefixOnlyClientHello(), labContext, enabledGate),
            StrategyRefusalReason.MALFORMED_LENGTH,
        )
    }

    @Test
    fun sessionIdCannotRunPastTheHandshakeBody() {
        val payload = validClientHello().apply { this[43] = 33 }

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.MALFORMED_LENGTH,
        )
    }

    @Test
    fun cipherSuiteVectorMustBeNonEmptyAndEven() {
        val payload = validClientHello().apply {
            this[44] = 0
            this[45] = 1
        }

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.MALFORMED_LENGTH,
        )
    }

    @Test
    fun compressionMethodsVectorMustNotBeEmpty() {
        val payload = validClientHello().apply { this[48] = 0 }

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.MALFORMED_LENGTH,
        )
    }

    @Test
    fun mismatchedHandshakeLengthIsRejected() {
        val payload = validClientHello().apply { this[8] = 40 }

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.MALFORMED_LENGTH,
        )
    }

    @Test
    fun trailingBytesAreRejected() {
        val original = validClientHello()
        val payload = original.copyOf(original.size + 1)

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.MALFORMED_LENGTH,
        )
    }

    @Test
    fun oversizedPayloadIsRejected() {
        val payload = ByteArray(16 * 1024 + 1)
        payload[0] = 0x16
        payload[1] = 0x03
        payload[2] = 0x03

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.PAYLOAD_TOO_LARGE,
        )
    }

    private fun assertRefused(plan: StrategyPlan, reason: StrategyRefusalReason) {
        assertTrue(plan is StrategyPlan.Refused)
        assertEquals(reason, (plan as StrategyPlan.Refused).reason)
    }

    private fun validClientHello(): ByteArray = LabTlsClientHello.create(
        randomBytes = ByteArray(LabTlsClientHello.RANDOM_BYTES) { index ->
            (index + 1).toByte()
        },
    )

    private fun legacyPrefixOnlyClientHello(): ByteArray {
        val body = ByteArray(35)
        body[0] = 0x03
        body[1] = 0x03
        body[34] = 0

        val handshake = ByteArray(4 + body.size)
        handshake[0] = 0x01
        handshake[3] = body.size.toByte()
        body.copyInto(handshake, destinationOffset = 4)

        return ByteArray(5 + handshake.size).also { record ->
            record[0] = 0x16
            record[1] = 0x03
            record[2] = 0x03
            record[4] = handshake.size.toByte()
            handshake.copyInto(record, destinationOffset = 5)
        }
    }

    private fun List<ByteArray>.flattenBytes(): ByteArray {
        val output = ByteArray(sumOf(ByteArray::size))
        var offset = 0
        forEach { segment ->
            segment.copyInto(output, destinationOffset = offset)
            offset += segment.size
        }
        return output
    }
}
