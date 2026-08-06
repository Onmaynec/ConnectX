package dev.connectx.vpn.nativebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTunBridgeInstrumentedTest {
    @Test
    fun nativeLibraryLoadsAndJniFailuresRemainControlled() {
        assertTrue(
            NativeTunBridge.loadError() ?: "native bridge did not load",
            NativeTunBridge.isAvailable(),
        )

        repeat(2) {
            val report = NativeTunBridge.runtimeSmokeTest().getOrThrow()

            assertTrue(
                report.version,
                report.version.startsWith("connectx-go-bridge/0.3.0-alpha.4"),
            )
            assertTrue(
                report.version,
                report.version.contains(
                    "8dda19e8e4613e014f0b12f3e624fdff5e5f23b3",
                ),
            )
            assertEquals(
                NativeTunBridge.CODE_INVALID_INPUT,
                report.invalidStartCode,
            )
            assertTrue(
                report.invalidStartError,
                report.invalidStartError.contains("invalid TUN file descriptor"),
            )
            assertEquals(NativeTunBridge.CODE_OK, report.stopCode)
            assertFalse(report.runningAfterStop)
            assertFalse(NativeTunBridge.isRunning())
        }
    }
}
