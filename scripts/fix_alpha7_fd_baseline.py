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
    "        evidenceFdBefore = currentOpenFdCount()\n\n        val evidenceGeneration = generation + 1L\n",
    "        val evidenceGeneration = generation + 1L\n",
)
replace_once(
    '''        check(payload.contentEquals(plan.reconstruct())) {
            "Strategy planner изменил reconstructed ClientHello"
        }

        checkGeneration(expectedGeneration)
''',
    '''        check(payload.contentEquals(plan.reconstruct())) {
            "Strategy planner изменил reconstructed ClientHello"
        }

        val preparedVersion = prepareNativeRuntime()
        nativeVersion = preparedVersion
        evidenceFdBefore = currentOpenFdCount()

        checkGeneration(expectedGeneration)
''',
)
replace_once(
    '''    private fun startNativeSession(
        tunnel: ParcelFileDescriptor,
        relayPort: Int,
    ): String {
        check(NativeTunBridge.isAvailable()) {
            NativeTunBridge.loadError()
                ?: "Native bridge недоступен для ABI этого устройства"
        }
        val version = NativeTunBridge.version().getOrElse { error ->
            throw IllegalStateException("JNI version self-check завершился ошибкой", error)
        }
''',
    '''    private fun prepareNativeRuntime(): String {
        check(NativeTunBridge.isAvailable()) {
            NativeTunBridge.loadError()
                ?: "Native bridge недоступен для ABI этого устройства"
        }
        return NativeTunBridge.version().getOrElse { error ->
            throw IllegalStateException("JNI version self-check завершился ошибкой", error)
        }
    }

    private fun startNativeSession(
        tunnel: ParcelFileDescriptor,
        relayPort: Int,
    ): String {
        val version = checkNotNull(nativeVersion) {
            "Native runtime не подготовлен до FD baseline"
        }
''',
)
PATH.write_text(text, encoding="utf-8")
