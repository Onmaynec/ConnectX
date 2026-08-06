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
import dev.connectx.strategy.api.StrategyEvaluationDecision
import dev.connectx.strategy.api.StrategyEvaluationReason
import dev.connectx.strategy.api.StrategySessionGateState
import dev.connectx.strategy.api.TlsClientHelloSplitStrategy
import dev.connectx.strategy.api.TlsRecordKind
import dev.connectx.vpn.api.TunnelContract
import dev.connectx.vpn.nativebridge.NativeTunBridge
import dev.connectx.vpn.relay.LoopbackTlsEvidenceServer
import dev.connectx.vpn.relay.LoopbackTlsEvidenceStats
import dev.connectx.vpn.service.ConnectXExternalTlsEvidenceService
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
class ExternalTlsEvidenceInstrumentedTest {
    @Test
    fun threeEvidenceSessionsTraverseRealTunAndStayWithinFdBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val statuses = LinkedBlockingQueue<Intent>()
        val responder = LoopbackTlsEvidenceServer()
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
            val responderPort = responder.start()
            assertTrue(responderPort in 1..65535)

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

            repeat(SESSIONS_PER_TEST) { sessionIndex ->
                ContextCompat.startForegroundService(
                    context,
                    evidenceServiceIntent(
                        context = context,
                        action = TunnelContract.ACTION_START,
                        responderPort = responderPort,
                    ),
                )
                val result = awaitTerminalStatus(statuses)
                assertSuccessfulEvidenceResult(
                    result = result,
                    responder = responder,
                    expectedTotalConnections = CONNECTIONS_PER_SESSION * (sessionIndex + 1L),
                )
                assertFalse(NativeTunBridge.isRunning())
            }
        } finally {
            runCatching {
                context.startService(
                    evidenceServiceIntent(
                        context = context,
                        action = TunnelContract.ACTION_STOP,
                        responderPort = responder.port().coerceAtLeast(1),
                    ),
                )
            }
            responder.close()
            runCatching { context.unregisterReceiver(receiver) }
            instrumentation.runOnMainSync { activity?.finish() }
            shell("appops set $packageName ACTIVATE_VPN default")
        }
    }

    private fun assertSuccessfulEvidenceResult(
        result: Intent,
        responder: LoopbackTlsEvidenceServer,
        expectedTotalConnections: Long,
    ) {
        val nativeTrace = NativeTunBridge.transportDiagnostics()
        val failure = buildString {
            append(result.getStringExtra(TunnelContract.EXTRA_ERROR))
            append("; nativeTrace=")
            append(nativeTrace)
            append("; responder=")
            append(responder.stats())
        }
        assertEquals(
            failure,
            TunnelContract.STATUS_EXTERNAL_TLS_EVIDENCE_COMPLETED,
            result.getStringExtra(TunnelContract.EXTRA_STATUS),
        )
        assertEquals(
            TunnelContract.MODE_NATIVE_EXTERNAL_TLS_EVIDENCE,
            result.getStringExtra(TunnelContract.EXTRA_ENGINE_MODE),
        )
        assertEquals(
            TEST_HOSTNAME,
            result.getStringExtra(TunnelContract.EXTRA_EVIDENCE_HOSTNAME),
        )
        assertEquals(
            TEST_PUBLIC_IPV4,
            result.getStringExtra(TunnelContract.EXTRA_EVIDENCE_RESOLVED_IPV4),
        )
        assertEquals(
            443,
            result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_TARGET_PORT, 0),
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
            EXPECTED_SPLIT_OFFSET,
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
        val fdBefore = result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_FD_BEFORE, -1)
        val fdAfter = result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_FD_AFTER, -1)
        val fdDelta = result.getIntExtra(
            TunnelContract.EXTRA_EVIDENCE_FD_DELTA,
            Int.MIN_VALUE,
        )
        assertTrue("missing FD baseline", fdBefore >= 0)
        assertTrue("missing FD teardown sample", fdAfter >= 0)
        assertEquals(fdAfter - fdBefore, fdDelta)
        assertTrue(
            "FD delta $fdDelta exceeded budget $FD_ALLOWED_DELTA",
            fdDelta <= FD_ALLOWED_DELTA,
        )
        assertEquals(
            3,
            result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_BASELINE_SUCCESSES, 0),
        )
        assertEquals(
            0,
            result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_BASELINE_FAILURES, -1),
        )
        assertEquals(
            3,
            result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_STRATEGY_SUCCESSES, 0),
        )
        assertEquals(
            0,
            result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_STRATEGY_FAILURES, -1),
        )
        assertEquals(
            3,
            result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_RECOVERY_SUCCESSES, 0),
        )
        assertEquals(
            0,
            result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_RECOVERY_FAILURES, -1),
        )
        assertEquals(
            TlsRecordKind.ALERT.name,
            result.getStringExtra(TunnelContract.EXTRA_EVIDENCE_BASELINE_RECORD_KIND),
        )
        assertEquals(
            TlsRecordKind.ALERT.name,
            result.getStringExtra(TunnelContract.EXTRA_EVIDENCE_STRATEGY_RECORD_KIND),
        )
        assertEquals(
            TlsRecordKind.ALERT.name,
            result.getStringExtra(TunnelContract.EXTRA_EVIDENCE_RECOVERY_RECORD_KIND),
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
        assertNull(result.getStringExtra(TunnelContract.EXTRA_STRATEGY_BASELINE_FAILURE))
        assertNull(result.getStringExtra(TunnelContract.EXTRA_STRATEGY_PHASE_FAILURE))
        assertNull(result.getStringExtra(TunnelContract.EXTRA_STRATEGY_RECOVERY_FAILURE))
        assertTrue(
            result.getLongExtra(TunnelContract.EXTRA_PROBE_UPLOADED_BYTES, 0L) > 0L,
        )
        assertTrue(
            result.getLongExtra(TunnelContract.EXTRA_PROBE_DOWNLOADED_BYTES, 0L) >=
                TLS_HEADER_BYTES * CONNECTIONS_PER_SESSION,
        )
        assertTrue(
            result.getLongExtra(TunnelContract.EXTRA_PROBE_RELAY_CONNECTIONS, 0L) >=
                CONNECTIONS_PER_SESSION,
        )

        val responderStats = awaitResponderStats(
            responder = responder,
            expectedResponses = expectedTotalConnections,
        )
        assertEquals(expectedTotalConnections, responderStats.accepted)
        assertEquals(expectedTotalConnections, responderStats.responses)
        assertEquals(0L, responderStats.rejected)
        assertTrue(nativeTrace.contains("tcpFlows="))
    }

    private fun awaitTerminalStatus(statuses: LinkedBlockingQueue<Intent>): Intent {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val remainingNanos = deadline - System.nanoTime()
            val status = statuses.poll(remainingNanos, TimeUnit.NANOSECONDS)
                ?: break
            when (status.getStringExtra(TunnelContract.EXTRA_STATUS)) {
                TunnelContract.STATUS_EXTERNAL_TLS_EVIDENCE_COMPLETED,
                TunnelContract.STATUS_ERROR,
                -> return status
            }
        }
        fail("Timed out waiting for terminal external TLS evidence status")
        error("unreachable")
    }

    private fun awaitResponderStats(
        responder: LoopbackTlsEvidenceServer,
        expectedResponses: Long,
    ): LoopbackTlsEvidenceStats {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var stats = responder.stats()
        while (stats.responses < expectedResponses && System.nanoTime() < deadline) {
            Thread.sleep(20)
            stats = responder.stats()
        }
        return stats
    }

    private fun evidenceServiceIntent(
        context: Context,
        action: String,
        responderPort: Int,
    ): Intent = Intent(context, ConnectXExternalTlsEvidenceService::class.java).apply {
        this.action = action
        putExtra(TunnelContract.EXTRA_EVIDENCE_HOSTNAME, TEST_HOSTNAME)
        putExtra(TunnelContract.EXTRA_EVIDENCE_TEST_RESOLVED_IPV4, TEST_PUBLIC_IPV4)
        putExtra(TunnelContract.EXTRA_EVIDENCE_TEST_LOOPBACK_PORT, responderPort)
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
        const val TEST_HOSTNAME = "example.org"
        const val TEST_PUBLIC_IPV4 = "93.184.216.34"
        const val EXPECTED_SPLIT_OFFSET = 43
        const val CONNECTIONS_PER_SESSION = 9L
        const val SESSIONS_PER_TEST = 3
        const val FD_ALLOWED_DELTA = 4
        const val TLS_HEADER_BYTES = 5L
        const val PROBE_TIMEOUT_SECONDS = 90L
    }
}
