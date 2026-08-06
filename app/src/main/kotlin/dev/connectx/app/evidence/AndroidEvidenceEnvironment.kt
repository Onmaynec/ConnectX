package dev.connectx.app.evidence

import android.os.Build
import dev.connectx.strategy.api.ExternalTlsEvidenceDeviceClass
import dev.connectx.strategy.api.ExternalTlsEvidenceEnvironment
import dev.connectx.strategy.api.ExternalTlsEvidenceEnvironmentPolicy

internal fun currentAndroidEvidenceEnvironment(nativeAbi: String?): ExternalTlsEvidenceEnvironment {
    val emulatorSignals = listOf(
        Build.FINGERPRINT,
        Build.HARDWARE,
        Build.PRODUCT,
    ).joinToString(separator = " ").lowercase()
    val isEmulator = listOf(
        "generic",
        "ranchu",
        "goldfish",
        "sdk_gphone",
        "emulator",
    ).any { signal -> signal in emulatorSignals }

    return ExternalTlsEvidenceEnvironment(
        deviceClass = if (isEmulator) {
            ExternalTlsEvidenceDeviceClass.EMULATOR
        } else {
            ExternalTlsEvidenceDeviceClass.PHYSICAL
        },
        androidApi = Build.VERSION.SDK_INT,
        abiFamily = ExternalTlsEvidenceEnvironmentPolicy.classifyAbi(
            nativeAbi ?: Build.SUPPORTED_ABIS.firstOrNull(),
        ),
    )
}
