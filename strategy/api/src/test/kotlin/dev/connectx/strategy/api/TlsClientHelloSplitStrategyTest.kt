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
        val plan = strategy.plan(payload, labContext, enabledGate)

        assertTrue(plan is StrategyPlan.Segmented)
        plan as StrategyPlan.Segmented
        assertEquals(43, plan.splitOffset)
        assertEquals(2, plan.segments.size)
        assertEquals(43, plan.segments[0].size)
        assertEquals(1, plan.segments[1].size)
        assertArrayEquals(payload, plan.reconstruct())

        payload.fill(0)
        assertFalse(plan.reconstruct().all { it == 0.toByte() })
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
    fun truncatedRecordIsRejectedBeforeReadingPastBuffer() {
        val payload = validClientHello().copyOf(20)

        assertRefused(
            strategy.plan(payload, labContext, enabledGate),
            StrategyRefusalReason.TRUNCATED_RECORD,
        )
    }

    @Test
    fun mismatchedHandshakeLengthIsRejected() {
        val payload = validClientHello().apply { this[8] = 34 }

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

    private fun validClientHello(): ByteArray {
        val body = ByteArray(35)
        body[0] = 0x03
        body[1] = 0x03
        for (index in 2 until 34) {
            body[index] = index.toByte()
        }
        body[34] = 0

        val handshake = ByteArray(4 + body.size)
        handshake[0] = 0x01
        handshake[1] = 0
        handshake[2] = 0
        handshake[3] = body.size.toByte()
        body.copyInto(handshake, destinationOffset = 4)

        return ByteArray(5 + handshake.size).also { record ->
            record[0] = 0x16
            record[1] = 0x03
            record[2] = 0x03
            record[3] = 0
            record[4] = handshake.size.toByte()
            handshake.copyInto(record, destinationOffset = 5)
        }
    }
}
