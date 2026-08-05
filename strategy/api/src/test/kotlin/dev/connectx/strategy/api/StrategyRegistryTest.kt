package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyRegistryTest {
    @Test
    fun labRegistryContainsOnlyExplicitLabStrategy() {
        val registry = StrategyRegistry.labDefaults()
        val descriptors = registry.descriptors()

        assertEquals(1, descriptors.size)
        assertEquals(TlsClientHelloSplitStrategy.ID, descriptors.single().id)
        assertFalse(descriptors.single().requiresRoot)
        assertTrue(descriptors.single().reversible)
        assertEquals(
            setOf(
                StrategyCapability.TCP,
                StrategyCapability.IPV4,
                StrategyCapability.TLS,
            ),
            descriptors.single().capabilities,
        )
        assertNotNull(registry.find(TlsClientHelloSplitStrategy.ID))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsAreRejected() {
        StrategyRegistry(
            listOf(
                TlsClientHelloSplitStrategy(),
                TlsClientHelloSplitStrategy(),
            ),
        )
    }

    @Test
    fun rootStrategyRequiresRootAvailability() {
        val descriptor = StrategyDescriptor(
            id = StrategyId("root-lab-test"),
            displayName = "Root lab test",
            description = "Test-only descriptor",
            capabilities = setOf(StrategyCapability.ROOT),
            requiresRoot = true,
            reversible = true,
        )
        val gate = StrategyFeatureGate(
            globallyEnabled = true,
            enabledStrategies = setOf(descriptor.id),
        )
        val context = StrategyContext(
            transport = TransportProtocol.TCP,
            network = NetworkProtocol.IPV4,
            application = ApplicationProtocol.UNKNOWN,
            scope = StrategyScope.LAB_ONLY,
            rootAvailable = false,
        )

        assertFalse(gate.allows(descriptor, context))
        assertTrue(gate.allows(descriptor, context.copy(rootAvailable = true)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun strategyIdRejectsNonCanonicalValue() {
        StrategyId("TLS Split")
    }
}
