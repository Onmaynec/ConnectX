#!/usr/bin/env python3
from pathlib import Path

PATH = Path("vpn/service/src/main/kotlin/dev/connectx/vpn/service/ConnectXExternalTlsEvidenceService.kt")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one occurrence, found {count}: {old!r}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.content.Intent\n",
    "import android.content.Context\nimport android.content.Intent\n",
)
old_method = '''    private fun completeEvidence(
        evidenceGeneration: Long,
        outcome: Result<ExternalTlsEvidenceResult>,
    ) {
        if (evidenceGeneration != generation) return

        val completedVersion = nativeVersion
        val completedFdBefore = evidenceFdBefore
        val completedFdKindsBefore = evidenceFdKindsBefore
        val shouldCooldown = gateGeneration == evidenceGeneration
        val cleanupError = closeResources()
        val completedFdSnapshotAfter = currentFdSnapshot()
        val completedFdAfter = completedFdSnapshotAfter?.total
        Log.i(
            FD_LOG_TAG,
            "generation=$evidenceGeneration warmed=$processFdLifecycleWarmed " +
                "before=${completedFdKindsBefore ?: "UNKNOWN"} " +
                "after=${completedFdSnapshotAfter?.summary() ?: "UNKNOWN"}",
        )
        if (cleanupError == null && completedFdAfter != null) {
            processFdLifecycleWarmed = true
        }
        val result = outcome.getOrNull()
        if (result != null && cleanupError == null) {
            publishStatus(
                status = TunnelContract.STATUS_EXTERNAL_TLS_EVIDENCE_COMPLETED,
                nativeVersion = completedVersion,
                target = result.target,
                result = result,
                fdBefore = completedFdBefore,
                fdAfter = completedFdAfter,
            )
        } else {
            if (shouldCooldown) enterCooldownAfterFailure()
            gateGeneration = null
            val primary = outcome.exceptionOrNull()
                ?: IllegalStateException(
                    "External TLS evidence завершена, но ресурсы закрылись с ошибкой",
                )
            publishStatus(
                status = TunnelContract.STATUS_ERROR,
                nativeVersion = completedVersion,
                error = buildFailureMessage(primary, cleanupError),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
'''
new_method = '''    private fun completeEvidence(
        evidenceGeneration: Long,
        outcome: Result<ExternalTlsEvidenceResult>,
    ) {
        if (evidenceGeneration != generation) return

        val completedVersion = nativeVersion
        val completedFdBefore = evidenceFdBefore
        val completedFdKindsBefore = evidenceFdKindsBefore
        val shouldCooldown = gateGeneration == evidenceGeneration
        val cleanupError = closeResources()
        val result = outcome.getOrNull()
        if (result != null && cleanupError == null) {
            val statusContext = applicationContext
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            mainHandler.postDelayed(
                {
                    awaitStablePostTeardownFdSnapshot(
                        previous = null,
                        attemptsRemaining = POST_TEARDOWN_FD_MAX_ATTEMPTS,
                    ) { completedFdSnapshotAfter ->
                        val completedFdAfter = completedFdSnapshotAfter?.total
                        Log.i(
                            FD_LOG_TAG,
                            "generation=$evidenceGeneration warmed=$processFdLifecycleWarmed " +
                                "before=${completedFdKindsBefore ?: "UNKNOWN"} " +
                                "after=${completedFdSnapshotAfter?.summary() ?: "UNKNOWN"}",
                        )
                        if (completedFdAfter != null) {
                            processFdLifecycleWarmed = true
                        }
                        publishStatus(
                            status = TunnelContract.STATUS_EXTERNAL_TLS_EVIDENCE_COMPLETED,
                            nativeVersion = completedVersion,
                            target = result.target,
                            result = result,
                            fdBefore = completedFdBefore,
                            fdAfter = completedFdAfter,
                            context = statusContext,
                        )
                    }
                },
                POST_TEARDOWN_FD_POLL_MILLIS,
            )
            return
        }

        if (shouldCooldown) enterCooldownAfterFailure()
        gateGeneration = null
        val primary = outcome.exceptionOrNull()
            ?: IllegalStateException(
                "External TLS evidence завершена, но ресурсы закрылись с ошибкой",
            )
        publishStatus(
            status = TunnelContract.STATUS_ERROR,
            nativeVersion = completedVersion,
            error = buildFailureMessage(primary, cleanupError),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
'''
replace_once(old_method, new_method)
replace_once(
    '''    private fun currentFdSnapshot(): FdSnapshot? = runCatching {
''',
    '''    private fun awaitStablePostTeardownFdSnapshot(
        previous: FdSnapshot?,
        attemptsRemaining: Int,
        completion: (FdSnapshot?) -> Unit,
    ) {
        val current = currentFdSnapshot()
        if (attemptsRemaining <= 1 || (current != null && current == previous)) {
            completion(current)
            return
        }
        mainHandler.postDelayed(
            {
                awaitStablePostTeardownFdSnapshot(
                    previous = current,
                    attemptsRemaining = attemptsRemaining - 1,
                    completion = completion,
                )
            },
            POST_TEARDOWN_FD_POLL_MILLIS,
        )
    }

    private fun currentFdSnapshot(): FdSnapshot? = runCatching {
''',
)
replace_once(
    "        fdAfter: Int? = null,\n    ) {\n",
    "        fdAfter: Int? = null,\n"
    "        context: Context = this,\n"
    "    ) {\n",
)
replace_once(
    "            setPackage(packageName)\n",
    "            setPackage(context.packageName)\n",
)
replace_once(
    "        sendBroadcast(intent)\n",
    "        context.sendBroadcast(intent)\n",
)
replace_once(
    "        const val FD_LOG_TAG = \"ConnectX-FD\"\n",
    "        const val FD_LOG_TAG = \"ConnectX-FD\"\n"
    "        const val POST_TEARDOWN_FD_POLL_MILLIS = 100L\n"
    "        const val POST_TEARDOWN_FD_MAX_ATTEMPTS = 8\n",
)
PATH.write_text(text, encoding="utf-8")
