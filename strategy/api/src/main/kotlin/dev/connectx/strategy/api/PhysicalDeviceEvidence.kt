package dev.connectx.strategy.api

enum class ExternalTlsEvidenceDeviceClass {
    PHYSICAL,
    EMULATOR,
    UNKNOWN,
}

enum class ExternalTlsEvidenceAbiFamily {
    ARM64,
    X86_64,
    OTHER,
    UNKNOWN,
}

enum class ExternalTlsEvidenceFdStatus {
    WITHIN_BUDGET,
    EXCEEDED,
    UNKNOWN,
}

data class ExternalTlsEvidenceEnvironment(
    val deviceClass: ExternalTlsEvidenceDeviceClass,
    val androidApi: Int?,
    val abiFamily: ExternalTlsEvidenceAbiFamily,
)

data class ExternalTlsEvidenceFdSample(
    val before: Int?,
    val after: Int?,
    val allowedDelta: Int = DEFAULT_ALLOWED_FD_DELTA,
)

data class ExternalTlsEvidenceFdAssessment(
    val status: ExternalTlsEvidenceFdStatus,
    val delta: Int?,
    val allowedDelta: Int,
)

object ExternalTlsEvidenceEnvironmentPolicy {
    fun classifyAbi(rawAbi: String?): ExternalTlsEvidenceAbiFamily = when {
        rawAbi.isNullOrBlank() -> ExternalTlsEvidenceAbiFamily.UNKNOWN
        rawAbi.equals("arm64-v8a", ignoreCase = true) ||
            rawAbi.contains("aarch64", ignoreCase = true) ->
            ExternalTlsEvidenceAbiFamily.ARM64
        rawAbi.equals("x86_64", ignoreCase = true) ||
            rawAbi.contains("amd64", ignoreCase = true) ->
            ExternalTlsEvidenceAbiFamily.X86_64
        else -> ExternalTlsEvidenceAbiFamily.OTHER
    }

    fun assessFd(sample: ExternalTlsEvidenceFdSample): ExternalTlsEvidenceFdAssessment {
        val before = sample.before
        val after = sample.after
        val allowed = sample.allowedDelta
        if (before == null || after == null || before < 0 || after < 0 || allowed < 0) {
            return ExternalTlsEvidenceFdAssessment(
                status = ExternalTlsEvidenceFdStatus.UNKNOWN,
                delta = null,
                allowedDelta = allowed.coerceAtLeast(0),
            )
        }
        val delta = after - before
        return ExternalTlsEvidenceFdAssessment(
            status = if (delta <= allowed) {
                ExternalTlsEvidenceFdStatus.WITHIN_BUDGET
            } else {
                ExternalTlsEvidenceFdStatus.EXCEEDED
            },
            delta = delta,
            allowedDelta = allowed,
        )
    }
}

const val DEFAULT_ALLOWED_FD_DELTA: Int = 4
