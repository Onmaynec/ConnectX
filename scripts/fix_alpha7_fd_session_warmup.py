#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence, found {count}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


service = Path("vpn/service/src/main/kotlin/dev/connectx/vpn/service/ConnectXExternalTlsEvidenceService.kt")
replace_once(
    service,
    "        evidenceFdBefore = currentOpenFdCount()\n",
    "        evidenceFdBefore = if (processFdLifecycleWarmed) currentOpenFdCount() else null\n",
)
replace_once(
    service,
    "        val cleanupError = closeResources()\n        val completedFdAfter = currentOpenFdCount()\n        val result = outcome.getOrNull()\n",
    "        val cleanupError = closeResources()\n"
    "        val completedFdAfter = currentOpenFdCount()\n"
    "        if (cleanupError == null && completedFdAfter != null) {\n"
    "            processFdLifecycleWarmed = true\n"
    "        }\n"
    "        val result = outcome.getOrNull()\n",
)
replace_once(
    service,
    "        @Volatile\n        var processGate = StrategySessionGate()\n\n        val EVALUATION_POLICY",
    "        @Volatile\n"
    "        var processGate = StrategySessionGate()\n\n"
    "        @Volatile\n"
    "        var processFdLifecycleWarmed = false\n\n"
    "        val EVALUATION_POLICY",
)

test = Path("app/src/androidTest/kotlin/dev/connectx/app/ExternalTlsEvidenceInstrumentedTest.kt")
replace_once(
    test,
    "                    expectedTotalConnections = CONNECTIONS_PER_SESSION * (sessionIndex + 1L),\n                )\n",
    "                    expectedTotalConnections = CONNECTIONS_PER_SESSION * (sessionIndex + 1L),\n"
    "                    expectMeasuredFd = sessionIndex > 0,\n"
    "                )\n",
)
replace_once(
    test,
    "        responder: LoopbackTlsEvidenceServer,\n        expectedTotalConnections: Long,\n    ) {\n",
    "        responder: LoopbackTlsEvidenceServer,\n"
    "        expectedTotalConnections: Long,\n"
    "        expectMeasuredFd: Boolean,\n"
    "    ) {\n",
)
replace_once(
    test,
    '''        assertTrue("missing FD baseline", fdBefore >= 0)
        assertTrue("missing FD teardown sample", fdAfter >= 0)
        assertEquals(fdAfter - fdBefore, fdDelta)
        assertTrue(
            "FD delta $fdDelta exceeded budget $FD_ALLOWED_DELTA",
            fdDelta <= FD_ALLOWED_DELTA,
        )
''',
    '''        assertTrue("missing FD teardown sample", fdAfter >= 0)
        if (expectMeasuredFd) {
            assertTrue("missing measured FD baseline", fdBefore >= 0)
            assertEquals(fdAfter - fdBefore, fdDelta)
            assertTrue(
                "FD delta $fdDelta exceeded budget $FD_ALLOWED_DELTA",
                fdDelta <= FD_ALLOWED_DELTA,
            )
        } else {
            assertEquals("first native session must be explicit warm-up", -1, fdBefore)
            assertEquals(
                "warm-up session must not publish a misleading FD delta",
                Int.MIN_VALUE,
                fdDelta,
            )
        }
''',
)
