package dev.connectx.app

import dev.connectx.strategy.api.HostnameValidationResult
import dev.connectx.strategy.api.TargetRejectionReason

/**
 * Exposes a rejection reason only after callers have excluded the valid result.
 * Keeping this conversion in one place avoids unsafe casts in Activity code.
 */
internal val HostnameValidationResult.reason: TargetRejectionReason
    get() = when (this) {
        is HostnameValidationResult.Rejected -> reason
        is HostnameValidationResult.Valid -> error(
            "A valid hostname result does not contain a rejection reason",
        )
    }
