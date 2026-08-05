package dev.connectx.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.connectx.strategy.api.ApplicationProtocol
import dev.connectx.strategy.api.LabTlsClientHello
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
        assertEquals("0.3.0-alpha.3", BuildConfig.VERSION_NAME)

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

        val payload = LabTlsClientHello.create(
            ByteArray(LabTlsClientHello.RANDOM_BYTES) { index ->
                (index * 3).toByte()
            },
        )
        assertEquals(LabTlsClientHello.PAYLOAD_BYTES, payload.size)

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
        assertEquals(LabTlsClientHello.SPLIT_OFFSET, enabled.splitOffset)
        assertArrayEquals(payload, enabled.reconstruct())
    }
}
