package dev.connectx.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.service.ConnectXTunnelService
import java.io.FileInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeUdpProbeInstrumentedTest {
    @Test
    fun boundedUdpProbeTraversesRealVpnTunNativeStackAndAuthenticatedRelay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val statuses = LinkedBlockingQueue<Intent>()
        var activity: Activity? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { statuses.offer(Intent(it)) }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(TunnelContract.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            shell("appops set $packageName ACTIVATE_VPN allow")
            val appOp = shell("appops get $packageName ACTIVATE_VPN")
            assertTrue(appOp, appOp.contains("allow", ignoreCase = true))
            assertNull(
                "VPN app-op did not prepare the test package: $appOp",
                VpnService.prepare(context),
            )

            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            instrumentation.waitForIdleSync()

            val startIntent = Intent(context, ConnectXTunnelService::class.java).apply {
                action = TunnelContract.ACTION_START
                putExtra(
                    TunnelContract.EXTRA_ENGINE_MODE,
                    TunnelContract.MODE_NATIVE_UDP_PROBE,
                )
            }
            ContextCompat.startForegroundService(context, startIntent)

            val result = awaitTerminalStatus(statuses)
            assertEquals(
                result.getStringExtra(TunnelContract.EXTRA_ERROR),
                TunnelContract.STATUS_UDP_PROBE_SUCCEEDED,
                result.getStringExtra(TunnelContract.EXTRA_STATUS),
            )
            assertEquals(
                TunnelContract.MODE_NATIVE_UDP_PROBE,
                result.getStringExtra(TunnelContract.EXTRA_ENGINE_MODE),
            )
            assertTrue(
                result.getStringExtra(TunnelContract.EXTRA_NATIVE_VERSION).orEmpty(),
                result.getStringExtra(TunnelContract.EXTRA_NATIVE_VERSION)
                    .orEmpty()
                    .startsWith("connectx-go-bridge/0.2.0-alpha.5"),
            )
            assertTrue(
                result.getLongExtra(TunnelContract.EXTRA_PROBE_LATENCY_MILLIS, 0L) >= 1L,
            )
            assertTrue(
                result.getLongExtra(TunnelContract.EXTRA_PROBE_UPLOADED_BYTES, 0L) >=
                    PROBE_PAYLOAD_BYTES,
            )
            assertTrue(
                result.getLongExtra(TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES, 0L) >=
                    PROBE_PAYLOAD_BYTES,
            )
            assertTrue(
                result.getLongExtra(TunnelContract.EXTRA_PROBE_RELAY_ASSOCIATIONS, 0L) >= 1L,
            )
            assertTrue(
                result.getLongExtra(TunnelContract.EXTRA_PROBE_DATAGRAMS, 0L) >= 1L,
            )
            assertFalse(NativeTunBridge.isRunning())
        } finally {
            val stopIntent = Intent(context, ConnectXTunnelService::class.java).apply {
                action = TunnelContract.ACTION_STOP
            }
            runCatching { context.startService(stopIntent) }
            runCatching { context.unregisterReceiver(receiver) }
            instrumentation.runOnMainSync { activity?.finish() }
            shell("appops set $packageName ACTIVATE_VPN default")
        }
    }

    private fun awaitTerminalStatus(statuses: LinkedBlockingQueue<Intent>): Intent {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val remainingNanos = deadline - System.nanoTime()
            val status = statuses.poll(remainingNanos, TimeUnit.NANOSECONDS)
                ?: break
            when (status.getStringExtra(TunnelContract.EXTRA_STATUS)) {
                TunnelContract.STATUS_UDP_PROBE_SUCCEEDED,
                TunnelContract.STATUS_ERROR,
                -> return status
            }
        }
        fail("Timed out waiting for terminal native UDP probe status")
        error("unreachable")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return descriptor.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader ->
                reader.readText()
            }
        }
    }

    private companion object {
        const val PROBE_PAYLOAD_BYTES = 64L
        const val PROBE_TIMEOUT_SECONDS = 40L
    }
}
