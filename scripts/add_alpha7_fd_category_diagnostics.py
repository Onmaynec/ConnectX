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
    "import android.os.SystemClock\n",
    "import android.os.SystemClock\nimport android.system.Os\nimport android.util.Log\n",
)
replace_once(
    "    private var evidenceFdBefore: Int? = null\n",
    "    private var evidenceFdBefore: Int? = null\n"
    "    private var evidenceFdKindsBefore: String? = null\n",
)
replace_once(
    "        evidenceFdBefore = if (processFdLifecycleWarmed) currentOpenFdCount() else null\n",
    "        val fdBaseline = if (processFdLifecycleWarmed) currentFdSnapshot() else null\n"
    "        evidenceFdBefore = fdBaseline?.total\n"
    "        evidenceFdKindsBefore = fdBaseline?.summary()\n",
)
replace_once(
    "        val completedFdBefore = evidenceFdBefore\n        val shouldCooldown = gateGeneration == evidenceGeneration\n",
    "        val completedFdBefore = evidenceFdBefore\n"
    "        val completedFdKindsBefore = evidenceFdKindsBefore\n"
    "        val shouldCooldown = gateGeneration == evidenceGeneration\n",
)
replace_once(
    "        val cleanupError = closeResources()\n        val completedFdAfter = currentOpenFdCount()\n",
    "        val cleanupError = closeResources()\n"
    "        val completedFdSnapshotAfter = currentFdSnapshot()\n"
    "        val completedFdAfter = completedFdSnapshotAfter?.total\n"
    "        Log.i(\n"
    "            FD_LOG_TAG,\n"
    "            \"generation=$evidenceGeneration warmed=$processFdLifecycleWarmed \" +\n"
    "                \"before=${completedFdKindsBefore ?: \"UNKNOWN\"} \" +\n"
    "                \"after=${completedFdSnapshotAfter?.summary() ?: \"UNKNOWN\"}\",\n"
    "        )\n",
)
replace_once(
    "        evidenceFdBefore = null\n        return firstError\n",
    "        evidenceFdBefore = null\n"
    "        evidenceFdKindsBefore = null\n"
    "        return firstError\n",
)
replace_once(
    '''    private fun currentOpenFdCount(): Int? = runCatching {
        File("/proc/self/fd").list()?.size
    }.getOrNull()

    private fun hasActiveResources(): Boolean =
''',
    '''    private fun currentFdSnapshot(): FdSnapshot? = runCatching {
        val directory = File("/proc/self/fd")
        val entries = directory.listFiles() ?: return@runCatching null
        var sockets = 0
        var pipes = 0
        var anonInodes = 0
        var devices = 0
        var files = 0
        var other = 0
        entries.forEach { entry ->
            val target = runCatching { Os.readlink(entry.absolutePath) }.getOrNull()
            when {
                target == null -> other += 1
                target.startsWith("socket:") -> sockets += 1
                target.startsWith("pipe:") -> pipes += 1
                target.startsWith("anon_inode:") -> anonInodes += 1
                target.startsWith("/dev/") -> devices += 1
                target.startsWith("/") -> files += 1
                else -> other += 1
            }
        }
        FdSnapshot(
            total = entries.size,
            sockets = sockets,
            pipes = pipes,
            anonInodes = anonInodes,
            devices = devices,
            files = files,
            other = other,
        )
    }.getOrNull()

    private fun hasActiveResources(): Boolean =
''',
)
replace_once(
    "    private data class EvidenceRequest(\n",
    '''    private data class FdSnapshot(
        val total: Int,
        val sockets: Int,
        val pipes: Int,
        val anonInodes: Int,
        val devices: Int,
        val files: Int,
        val other: Int,
    ) {
        fun summary(): String =
            "total=$total,socket=$sockets,pipe=$pipes,anon=$anonInodes," +
                "device=$devices,file=$files,other=$other"
    }

    private data class EvidenceRequest(
''',
)
replace_once(
    "        const val SAMPLES_PER_PHASE = 3\n",
    "        const val SAMPLES_PER_PHASE = 3\n"
    "        const val FD_LOG_TAG = \"ConnectX-FD\"\n",
)
PATH.write_text(text, encoding="utf-8")
