#!/usr/bin/env python3
from pathlib import Path
import json

OLD = "0.3.0-alpha.6"
NEW = "0.3.0-alpha.7"


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence, found {count}: {old!r}")
    write(path, text.replace(old, new, 1))


def replace_all(path: str, old: str, new: str) -> bool:
    text = read(path)
    if old not in text:
        return False
    write(path, text.replace(old, new))
    return True


for directory in ("app", "strategy", "vpn", "engine/go/bridge"):
    for path in Path(directory).rglob("*"):
        if path.is_file() and path.suffix in {".kt", ".kts", ".go"}:
            replace_all(str(path), OLD, NEW)
            replace_all(str(path), "v0.3 alpha.6", "v0.3 alpha.7")

replace_once("app/build.gradle.kts", "versionCode = 13", "versionCode = 14")

guard_test = "scripts/tests/test_release_guard.py"
replace_all(guard_test, OLD, NEW)
replace_all(guard_test, '"version_code": 13', '"version_code": 14')
replace_all(guard_test, "versionCode = 13", "versionCode = 14")
replace_all(guard_test, "alpha6-scope.md", "alpha7-scope.md")

manifest = json.loads(read("release/prerelease.json"))
manifest.update(
    version_name=NEW,
    version_code=14,
    tag=f"v{NEW}",
    title=f"ConnectX v{NEW} — Physical Device Evidence Kit",
    notes_path=f"docs/releases/v{NEW}.md",
    scope_path="docs/roadmap/alpha7-scope.md",
)
write("release/prerelease.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

verify = read("scripts/verify-alpha6-local.sh").replace("alpha6", "alpha7").replace(OLD, NEW)
write("scripts/verify-alpha7-local.sh", verify)

panel = "app/src/main/kotlin/dev/connectx/app/evidence/ExternalTlsEvidencePanel.kt"
replace_once(
    panel,
    "import dev.connectx.strategy.api.ExternalTlsEvidenceAssessor\n",
    "import dev.connectx.strategy.api.DEFAULT_ALLOWED_FD_DELTA\n"
    "import dev.connectx.strategy.api.ExternalTlsEvidenceAssessor\n"
    "import dev.connectx.strategy.api.ExternalTlsEvidenceEnvironmentPolicy\n"
    "import dev.connectx.strategy.api.ExternalTlsEvidenceFdSample\n"
    "import dev.connectx.strategy.api.ExternalTlsEvidenceFdStatus\n",
)
replace_once(
    panel,
    "    val gateState: String? = null,\n    val error: String? = null,\n",
    "    val gateState: String? = null,\n"
    "    val nativeAbi: String? = null,\n"
    "    val fdBefore: Int? = null,\n"
    "    val fdAfter: Int? = null,\n"
    "    val fdDelta: Int? = null,\n"
    "    val error: String? = null,\n",
)
replace_once(
    panel,
    "            gateState = gateState,\n        ),\n",
    "            gateState = gateState,\n"
    "            environment = currentAndroidEvidenceEnvironment(nativeAbi),\n"
    "            fdBefore = fdBefore,\n"
    "            fdAfter = fdAfter,\n"
    "            fdAllowedDelta = DEFAULT_ALLOWED_FD_DELTA,\n"
    "        ),\n",
)
replace_once(
    panel,
    "Alpha.7 выполняет по три попытки baseline, TLS split и recovery, классифицирует доказательность и проверяет отчёт по allow-list schema.",
    "Alpha.7 выполняет по три попытки baseline, TLS split и recovery, измеряет FD после полного teardown и формирует privacy-safe schema v3 для проверки на физическом устройстве.",
)
replace_once(
    panel,
    "    state.reason?.let { evidenceRecommendationLine(state) },\n    state.reason?.let { \"Reason: $it\" },\n",
    "    state.reason?.let { evidenceRecommendationLine(state) },\n"
    "    state.reason?.let { deviceEnvironmentLine(state) },\n"
    "    state.reason?.let { fdLifecycleLine(state) },\n"
    "    state.reason?.let { \"Reason: $it\" },\n",
)
replace_once(
    panel,
    "private fun ExternalTlsEvidenceUiState.toEvidenceSummary() =\n",
    '''private fun deviceEnvironmentLine(state: ExternalTlsEvidenceUiState): String {
    val environment = currentAndroidEvidenceEnvironment(state.nativeAbi)
    return "Устройство: ${environment.deviceClass.name} · API ${environment.androidApi ?: "UNKNOWN"} · ${environment.abiFamily.name}"
}

private fun fdLifecycleLine(state: ExternalTlsEvidenceUiState): String {
    val assessment = ExternalTlsEvidenceEnvironmentPolicy.assessFd(
        ExternalTlsEvidenceFdSample(
            before = state.fdBefore,
            after = state.fdAfter,
            allowedDelta = DEFAULT_ALLOWED_FD_DELTA,
        ),
    )
    val status = when (assessment.status) {
        ExternalTlsEvidenceFdStatus.WITHIN_BUDGET -> "в пределах бюджета"
        ExternalTlsEvidenceFdStatus.EXCEEDED -> "превышение бюджета"
        ExternalTlsEvidenceFdStatus.UNKNOWN -> "данных недостаточно"
    }
    return "FD lifecycle: ${state.fdBefore ?: "?"} → ${state.fdAfter ?: "?"} · delta ${assessment.delta ?: "?"} · $status"
}

private fun ExternalTlsEvidenceUiState.toEvidenceSummary() =
''',
)

main_activity = "app/src/main/kotlin/dev/connectx/app/MainActivity.kt"
replace_once(
    main_activity,
    "                    gateState = intent.getStringExtra(\n                        TunnelContract.EXTRA_STRATEGY_GATE_STATE,\n                    ),\n                    error = null,\n",
    "                    gateState = intent.getStringExtra(\n"
    "                        TunnelContract.EXTRA_STRATEGY_GATE_STATE,\n"
    "                    ),\n"
    "                    nativeAbi = intent.getStringExtra(TunnelContract.EXTRA_NATIVE_ABI),\n"
    "                    fdBefore = intent.getIntExtra(\n"
    "                        TunnelContract.EXTRA_EVIDENCE_FD_BEFORE,\n"
    "                        -1,\n"
    "                    ).takeIf { it >= 0 },\n"
    "                    fdAfter = intent.getIntExtra(\n"
    "                        TunnelContract.EXTRA_EVIDENCE_FD_AFTER,\n"
    "                        -1,\n"
    "                    ).takeIf { it >= 0 },\n"
    "                    fdDelta = intent.getIntExtra(\n"
    "                        TunnelContract.EXTRA_EVIDENCE_FD_DELTA,\n"
    "                        Int.MIN_VALUE,\n"
    "                    ).takeIf { it != Int.MIN_VALUE },\n"
    "                    error = null,\n",
)

contract = "vpn/api/src/main/kotlin/dev/connectx/vpn/api/TunnelContract.kt"
replace_once(
    contract,
    "    const val EXTRA_EVIDENCE_RECOVERY_FAILURES =\n        \"dev.connectx.extra.EVIDENCE_RECOVERY_FAILURES\"\n",
    "    const val EXTRA_EVIDENCE_RECOVERY_FAILURES =\n"
    "        \"dev.connectx.extra.EVIDENCE_RECOVERY_FAILURES\"\n"
    "    const val EXTRA_EVIDENCE_FD_BEFORE =\n"
    "        \"dev.connectx.extra.EVIDENCE_FD_BEFORE\"\n"
    "    const val EXTRA_EVIDENCE_FD_AFTER =\n"
    "        \"dev.connectx.extra.EVIDENCE_FD_AFTER\"\n"
    "    const val EXTRA_EVIDENCE_FD_DELTA =\n"
    "        \"dev.connectx.extra.EVIDENCE_FD_DELTA\"\n",
)

service = "vpn/service/src/main/kotlin/dev/connectx/vpn/service/ConnectXExternalTlsEvidenceService.kt"
replace_once(service, "import java.io.DataInputStream\n", "import java.io.DataInputStream\nimport java.io.File\n")
replace_once(
    service,
    "    private var evidenceThread: Thread? = null\n    private var nativeVersion: String? = null\n",
    "    private var evidenceThread: Thread? = null\n"
    "    private var nativeVersion: String? = null\n"
    "    private var evidenceFdBefore: Int? = null\n",
)
replace_once(
    service,
    "        val evidenceGeneration = generation + 1L\n",
    "        evidenceFdBefore = currentOpenFdCount()\n\n        val evidenceGeneration = generation + 1L\n",
)
replace_once(
    service,
    "        val completedVersion = nativeVersion\n        val shouldCooldown = gateGeneration == evidenceGeneration\n        val cleanupError = closeResources()\n        val result = outcome.getOrNull()\n",
    "        val completedVersion = nativeVersion\n"
    "        val completedFdBefore = evidenceFdBefore\n"
    "        val shouldCooldown = gateGeneration == evidenceGeneration\n"
    "        val cleanupError = closeResources()\n"
    "        val completedFdAfter = currentOpenFdCount()\n"
    "        val result = outcome.getOrNull()\n",
)
replace_once(
    service,
    "                target = result.target,\n                result = result,\n            )\n",
    "                target = result.target,\n"
    "                result = result,\n"
    "                fdBefore = completedFdBefore,\n"
    "                fdAfter = completedFdAfter,\n"
    "            )\n",
)
replace_once(
    service,
    "        relayCredentials = null\n        nativeVersion = null\n        return firstError\n",
    "        relayCredentials = null\n"
    "        nativeVersion = null\n"
    "        evidenceFdBefore = null\n"
    "        return firstError\n",
)
replace_once(
    service,
    "    private fun hasActiveResources(): Boolean =\n",
    '''    private fun currentOpenFdCount(): Int? = runCatching {
        File("/proc/self/fd").list()?.size
    }.getOrNull()

    private fun hasActiveResources(): Boolean =
''',
)
replace_once(
    service,
    "        target: ExternalTlsEvidenceTarget? = null,\n        result: ExternalTlsEvidenceResult? = null,\n    ) {\n",
    "        target: ExternalTlsEvidenceTarget? = null,\n"
    "        result: ExternalTlsEvidenceResult? = null,\n"
    "        fdBefore: Int? = null,\n"
    "        fdAfter: Int? = null,\n"
    "    ) {\n",
)
replace_once(
    service,
    "            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }\n            target?.let { evidenceTarget ->\n",
    "            error?.let { putExtra(TunnelContract.EXTRA_ERROR, it) }\n"
    "            fdBefore?.let { putExtra(TunnelContract.EXTRA_EVIDENCE_FD_BEFORE, it) }\n"
    "            fdAfter?.let { putExtra(TunnelContract.EXTRA_EVIDENCE_FD_AFTER, it) }\n"
    "            if (fdBefore != null && fdAfter != null) {\n"
    "                putExtra(TunnelContract.EXTRA_EVIDENCE_FD_DELTA, fdAfter - fdBefore)\n"
    "            }\n"
    "            target?.let { evidenceTarget ->\n",
)

test_path = "app/src/androidTest/kotlin/dev/connectx/app/ExternalTlsEvidenceInstrumentedTest.kt"
test_text = read(test_path)
test_text = test_text.replace(
    "fun debuggableEvidencePathTraversesRealTunAndAllowsNextExplicitSession()",
    "fun threeEvidenceSessionsTraverseRealTunAndStayWithinFdBudget()",
)
old_sessions = '''            ContextCompat.startForegroundService(
                context,
                evidenceServiceIntent(
                    context = context,
                    action = TunnelContract.ACTION_START,
                    responderPort = responderPort,
                ),
            )
            val first = awaitTerminalStatus(statuses)
            assertSuccessfulEvidenceResult(
                result = first,
                responder = responder,
                expectedTotalConnections = CONNECTIONS_PER_SESSION,
            )
            assertFalse(NativeTunBridge.isRunning())

            // LAB_APPROVED is scoped to the completed attempt. A second explicit
            // user action must create a new bounded session without a fake
            // cooldown or leaked TUN/native resource from the first attempt.
            ContextCompat.startForegroundService(
                context,
                evidenceServiceIntent(
                    context = context,
                    action = TunnelContract.ACTION_START,
                    responderPort = responderPort,
                ),
            )
            val second = awaitTerminalStatus(statuses)
            assertSuccessfulEvidenceResult(
                result = second,
                responder = responder,
                expectedTotalConnections = CONNECTIONS_PER_SESSION * 2L,
            )
            assertFalse(NativeTunBridge.isRunning())
'''
new_sessions = '''            repeat(SESSIONS_PER_TEST) { sessionIndex ->
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
'''
if old_sessions not in test_text:
    raise SystemExit("ExternalTlsEvidenceInstrumentedTest session block not found")
test_text = test_text.replace(old_sessions, new_sessions, 1)
fd_anchor = '''        assertEquals(
            StrategySessionGateState.LAB_APPROVED.name,
            result.getStringExtra(TunnelContract.EXTRA_STRATEGY_GATE_STATE),
        )
'''
fd_assertions = fd_anchor + '''        val fdBefore = result.getIntExtra(TunnelContract.EXTRA_EVIDENCE_FD_BEFORE, -1)
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
'''
if fd_anchor not in test_text:
    raise SystemExit("FD assertion anchor not found")
test_text = test_text.replace(fd_anchor, fd_assertions, 1)
test_text = test_text.replace(
    "        const val CONNECTIONS_PER_SESSION = 9L\n",
    "        const val CONNECTIONS_PER_SESSION = 9L\n"
    "        const val SESSIONS_PER_TEST = 3\n"
    "        const val FD_ALLOWED_DELTA = 4\n",
)
write(test_path, test_text)

changelog = "CHANGELOG.md"
entry = '''## [0.3.0-alpha.7]

### Added

- Privacy-safe physical/emulator classification, Android API and broad ABI family for evidence reports.
- File-descriptor before/after/delta measurements taken around complete external evidence teardown.
- Typed bounded FD lifecycle assessment with explicit `WITHIN_BUDGET`, `EXCEEDED` and `UNKNOWN` states.
- Redacted report schema v3 with environment and FD aggregate fields in the deterministic report ID.
- Three sequential TEST-NET TUN/native evidence sessions in Android instrumentation.
- Strict physical arm64 evidence collector and allow-list bundle validator.
- Negative tests rejecting device identifiers, raw targets, URLs, credentials and unknown bundle fields.
- Application version `0.3.0-alpha.7`, versionCode `14`.
- Native bridge version `connectx-go-bridge/0.3.0-alpha.7`.

### Changed

- External evidence now reports FD lifecycle only after native session, TUN and relay teardown complete.
- Physical-device readiness evidence is separated from the manual restricted-network strategy claim.

### Safety boundaries

- No serial, model, manufacturer, fingerprint, SSID or network identifier is exported.
- TUN capture remains limited to TEST-NET-1 (`192.0.2.0/24`).
- Ordinary application traffic is not routed or modified.
- No HTTP, account data, MITM or HTTPS decryption is introduced.
- Issues #11 and #22 remain open until actual physical-device evidence is attached.

'''
replace_once(changelog, "## [0.3.0-alpha.6]\n", entry + "## [0.3.0-alpha.6]\n")
replace_all(
    changelog,
    "- Physical-device repeated TUN lifecycle verification.",
    "- Execute the alpha.7 kit on a physical arm64 device and attach its validated bundle.",
)

readme = "README.md"
replace_all(readme, "v0.3.0-alpha.6", "v0.3.0-alpha.7")
replace_all(readme, "Alpha.6", "Alpha.7")
replace_all(readme, "schema v2", "schema v3")
replace_all(readme, "scripts/verify-alpha6-local.sh", "scripts/verify-alpha7-local.sh")
replace_once(
    readme,
    "Alpha.7 сохраняет repeated TLS A/B/A-проверку и добавляет Evidence Quality Gate: результат получает формальный evidence class, уровень уверенности, безопасную рекомендацию и детерминированный report ID. Перед отправкой обезличенный отчёт проверяется по allow-list schema v3.",
    "Alpha.7 сохраняет Evidence Quality Gate и добавляет Physical Device Evidence Kit: broad device profile без уникальных идентификаторов, FD before/after после полного teardown, schema v3 report и строгий collector для физического arm64-устройства.",
)
replace_once(
    readme,
    "В интерфейсе показываются success/failure counters, median latency, TLS record kind, decision, reason, evidence class, confidence и безопасный следующий шаг. Обезличенный отчёт schema v3 не получает hostname, IPv4, payload, credentials или raw error text; неизвестные поля, URL и credential-like fragments отклоняются до share intent. Детерминированный `report_id` позволяет сравнить два одинаковых агрегированных результата без сетевого идентификатора.",
    "В интерфейсе показываются success/failure counters, median latency, TLS record kind, evidence class, confidence, broad device class/API/ABI family и FD delta после полного teardown. Обезличенный отчёт schema v3 не получает hostname, IPv4, payload, credentials, serial, model, fingerprint, SSID или raw error text. Детерминированный `report_id` учитывает только агрегированные безопасные поля.\n\n### Проверка физического устройства\n\nПосле source-build native bridge запустите `scripts/collect-alpha7-physical-evidence.sh`. Скрипт принимает только одно physical `arm64-v8a` устройство, выполняет JNI и трёхкратный TEST-NET evidence lifecycle, проверяет FD budget и создаёт allow-list bundle без уникальных идентификаторов. Подробности: [`docs/operations/physical-device-evidence.md`](docs/operations/physical-device-evidence.md). Реальная restricted-network серия всё равно выполняется вручную из UI.",
)
