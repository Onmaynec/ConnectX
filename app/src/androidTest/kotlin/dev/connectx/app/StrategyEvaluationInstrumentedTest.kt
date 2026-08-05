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
import dev.connectx.strategy.api.LabTlsClientHello
import dev.connectx.strategy.api.StrategyEvaluationDecision
import dev.connectx.strategy.api.StrategyEvaluationReason
import dev.connectx.strategy.api.StrategySessionGateState
import dev.connectx.strategy.api.TlsClientHelloSplitStrategy
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.service.ConnectXStrategyEvaluationService
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
class StrategyEvaluationInstrumentedTest {
    @Test
    fun baselineStrategyRecoveryTraverseRealTunAndProduceKeepDecision() {
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

            ContextCompat.startForegroundService(
                context,
                strategyServiceIntent(context, TunnelContract.ACTION_START),
            )

            val result = awaitTerminalStatus(statuses)
            val nativeTrace = NativeTunBridge.transportDiagnostics()
            val failure = buildString {
                append(result.getStringExtra(TunnelContract.EXTRA_ERROR))
                append("; nativeTrace=")
                append(nativeTrace)
            }
            assertEquals(
                failure,
                TunnelContract.STATUS_STRATEGY_EVALUATION_COMPLETED,
                result.getStringExtra(TunnelContract.EXTRA_STATUS),
            )
            assertEquals(
                TunnelContract.MODE_NATIVE_STRATEGY_EVALUATION,
                result.getStringExtra(TunnelContract.EXTRA_ENGINE_MODE),
            )
            assertEquals(
                TlsClientHelloSplitStrategy.ID.value,
                result.getStringExtra(TunnelContract.EXTRA_STRATEGY_ID),
            )
            assertEquals(
                2,
                result.getIntExtra(TunnelContract.EXTRA_STRATEGY_SEGMENTS, 0),
            )
            assertEquals(
                LabTlsClientHello.SPLIT_OFFSET,
                result.getIntExtra(TunnelContract.EXTRA_STRATEGY_SPLIT_OFFSET, 0),
            )
            assertEquals(
                StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION.name,
                result.getStringExtra(TunnelContract.EXTRA_STRATEGY_DECISION),
            )
            assertEquals(
                StrategyEvaluationReason.PASSED_WITHIN_LATENCY_BUDGET.name,
                result.getStringExtra(TunnelContract.EXTRA_STRATEGY_REASON),
            )
            assertEquals(
                StrategySessionGateState.LAB_APPROVED.name,
                result.getStringExtra(TunnelContract.EXTRA_STRATEGY_GATE_STATE),
            )
            assertTrue(
                result.getLongExtra(
                    TunnelContract.EXTRA_STRATEGY_BASELINE_LATENCY_MILLIS,
                    -1L,
                ) >= 1L,
            )
            assertTrue(
                result.getLongExtra(
                    TunnelContract.EXTRA_STRATEGY_LATENCY_MILLIS,
                    -1L,
                ) >= 1L,
            )
            assertTrue(
                result.getLongExtra(
                    TunnelContract.EXTRA_STRATEGY_RECOVERY_LATENCY_MILLIS,
                    -1L,
                ) >= 1L,
            )
            assertTrue(
                result.getLongExtra(
                    TunnelContract.EXTRA_STRATEGY_ALLOWED_LATENCY_MILLIS,
                    -1L,
                ) >= result.getLongExtra(
                    TunnelContract.EXTRA_STRATEGY_BASELINE_LATENCY_MILLIS,
                    -1L,
                ),
            )
            assertNull(
                result.getStringExtra(TunnelContract.EXTRA_STRATEGY_BASELINE_FAILURE),
            )
            assertNull(
                result.getStringExtra(TunnelContract.EXTRA_STRATEGY_PHASE_FAILURE),
            )
            assertNull(
                result.getStringExtra(TunnelContract.EXTRA_STRATEGY_RECOVERY_FAILURE),
            )
            val expectedPayloadBytes =
                LabTlsClientHello.PAYLOAD_BYTES.toLong() * EXPECTED_CONNECTIONS
            assertTrue(
                result.getLongExtra(TunnelContract.EXTRA_PROBE_UPLOADED_BYTES, 0L) >=
                    expectedPayloadBytes,
            )
            assertTrue(
                result.getLongExtra(TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES, 0L) >=
                    expectedPayloadBytes,
            )
            assertTrue(
                result.getLongExtra(TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS, 0L) >=
                    EXPECTED_CONNECTIONS,
            )
            assertTrue(nativeTrace.contains("tcpFlows="))
            assertFalse(NativeTunBridge.isRunning())

            // A duplicated STOP after the completed service has already torn
            // down must not convert LAB_APPROVED into a synthetic cooldown.
            context.startService(
                strategyServiceIntent(context, TunnelContract.ACTION_STOP),
            )
            assertEquals(
                TunnelContract.STATUS_STOPPED,
                awaitStatus(statuses, TunnelContract.STATUS_STOPPED)
                    .getStringExtra(TunnelContract.EXTRA_STATUS),
            )

            // Immediate explicit re-evaluation must still be accepted. Stop it
            // after STATUS_STARTED so this test also verifies active-stop cleanup.
            ContextCompat.startForegroundService(
                context,
                strategyServiceIntent(context, TunnelContract.ACTION_START),
            )
            assertEquals(
                TunnelContract.STATUS_STARTED,
                awaitStatus(statuses, TunnelContract.STATUS_STARTED)
                    .getStringExtra(TunnelContract.EXTRA_STATUS),
            )
            context.startService(
                strategyServiceIntent(context, TunnelContract.ACTION_STOP),
            )
            assertEquals(
                TunnelContract.STATUS_STOPPED,
                awaitStatus(statuses, TunnelContract.STATUS_STOPPED)
                    .getStringExtra(TunnelContract.EXTRA_STATUS),
            )
            assertFalse(NativeTunBridge.isRunning())
        } finally {
            runCatching {
                context.startService(
                    strategyServiceIntent(context, TunnelContract.ACTION_STOP),
                )
            }
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
                TunnelContract.STATUS_STRATEGY_EVALUATION_COMPLETED,
                TunnelContract.STATUS_ERROR,
                -> return status
            }
        }
        fail("Timed out waiting for terminal strategy evaluation status")
        error("unreachable")
    }

    private fun awaitStatus(
        statuses: LinkedBlockingQueue<Intent>,
        expectedStatus: String,
    ): Intent {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STATUS_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val remainingNanos = deadline - System.nanoTime()
            val status = statuses.poll(remainingNanos, TimeUnit.NANOSECONDS)
                ?: break
            val actual = status.getStringExtra(TunnelContract.EXTRA_STATUS)
            if (actual == expectedStatus) return status
            if (actual == TunnelContract.STATUS_ERROR) {
                fail(
                    "Expected $expectedStatus but service failed: " +
                        status.getStringExtra(TunnelContract.EXTRA_ERROR),
                )
            }
        }
        fail("Timed out waiting for strategy evaluation status $expectedStatus")
        error("unreachable")
    }

    private fun strategyServiceIntent(context: Context, action: String): Intent =
        Intent(context, ConnectXStrategyEvaluationService::class.java).apply {
            this.action = action
            putExtra(
                TunnelContract.EXTRA_ENGINE_MODE,
                TunnelContract.MODE_NATIVE_STRATEGY_EVALUATION,
            )
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
        const val EXPECTED_CONNECTIONS = 3L
        const val PROBE_TIMEOUT_SECONDS = 50L
        const val STATUS_TIMEOUT_SECONDS = 15L
    }
}
