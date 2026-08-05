package dev.connectx.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.connectx.strategy.api.ApplicationProtocol
import dev.connectx.strategy.api.NetworkProtocol
import dev.connectx.strategy.api.StrategyCapability
import dev.connectx.strategy.api.StrategyContext
import dev.connectx.strategy.api.StrategyFeatureGate
import dev.connectx.strategy.api.StrategyPlan
import dev.connectx.strategy.api.StrategyRefusalReason
import dev.connectx.strategy.api.StrategyScope
import dev.connectx.strategy.api.TlsClientHelloSplitStrategy
import dev.connectx.strategy.api.TransportProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrategyFoundationInstrumentedTest {
    @Test
    fun packagedStrategyIsDisabledByDefaultAndProducesLosslessLabPlanWhenExplicitlyEnabled() {
        assertEquals("0.3.0-alpha.1", BuildConfig.VERSION_NAME)

        val strategy = TlsClientHelloSplitStrategy()
        assertEquals(TlsClientHelloSplitStrategy.ID, strategy.descriptor.id)
        assertEquals(
            setOf(
                StrategyCapability.TCP,
                StrategyCapability.IPV4,
                StrategyCapability.TLS,
            ),
            strategy.descriptor.capabilities,
        )

        val payload = validClientHello()
        val context = StrategyContext(
            transport = TransportProtocol.TCP,
            network = NetworkProtocol.IPV4,
            application = ApplicationProtocol.TLS,
            scope = StrategyScope.LAB_ONLY,
        )

        val disabled = strategy.plan(
            payload = payload,
            context = context,
            featureGate = StrategyFeatureGate(),
        )
        assertTrue(disabled is StrategyPlan.Refused)
        assertEquals(
            StrategyRefusalReason.FEATURE_DISABLED,
            (disabled as StrategyPlan.Refused).reason,
        )

        val enabled = strategy.plan(
            payload = payload,
            context = context,
            featureGate = StrategyFeatureGate(
                globallyEnabled = true,
                enabledStrategies = setOf(TlsClientHelloSplitStrategy.ID),
            ),
        )
        assertTrue(enabled is StrategyPlan.Segmented)
        enabled as StrategyPlan.Segmented
        assertEquals(2, enabled.segments.size)
        assertEquals(43, enabled.splitOffset)
        assertArrayEquals(payload, enabled.reconstruct())
    }

    private fun validClientHello(): ByteArray {
        val body = ByteArray(35)
        body[0] = 0x03
        body[1] = 0x03
        for (index in 2 until 34) {
            body[index] = (index * 3).toByte()
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
