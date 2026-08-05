package dev.connectx.strategy.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsClientHelloFactoryTest {
    @Test
    fun generatedClientHelloPassesExistingStrategyPlanner() {
        val creation = TlsClientHelloFactory.create("example.org")
        assertTrue(
            "The platform TLS engine must produce a supported single-record ClientHello",
            creation is TlsClientHelloCreationResult.Created,
        )
        val payload = (creation as TlsClientHelloCreationResult.Created).payload

        val plan = TlsClientHelloSplitStrategy().plan(
            payload = payload,
            context = StrategyContext(
                transport = TransportProtocol.TCP,
                network = NetworkProtocol.IPV4,
                application = ApplicationProtocol.TLS,
                scope = StrategyScope.LAB_ONLY,
            ),
            featureGate = StrategyFeatureGate(
                globallyEnabled = true,
                enabledStrategies = setOf(TlsClientHelloSplitStrategy.ID),
            ),
        )

        assertTrue(plan is StrategyPlan.Segmented)
        val segmented = plan as StrategyPlan.Segmented
        assertEquals(2, segmented.segments.size)
        assertArrayEquals(payload, segmented.reconstruct())
    }

    @Test
    fun createdPayloadUsesDefensiveCopies() {
        val creation = TlsClientHelloFactory.create("example.org")
            as TlsClientHelloCreationResult.Created
        val first = creation.payload
        val original = first[0]
        first[0] = (original.toInt() xor 0xff).toByte()

        val second = creation.payload
        assertEquals(original, second[0])
        assertNotEquals(first[0], second[0])
    }

    @Test
    fun invalidHostnameIsRejectedBeforeTlsEngineUse() {
        assertEquals(
            TlsClientHelloCreationResult.Rejected(
                TlsClientHelloCreationFailure.INVALID_HOSTNAME,
            ),
            TlsClientHelloFactory.create("127.0.0.1"),
        )
    }
}
