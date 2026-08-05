@file:Suppress("FunctionName")

package dev.connectx.vpn.service

import dev.connectx.vpn.relay.RelayTarget

/**
 * Narrows the nullable instrumentation override only after the evidence service
 * has validated that the port is present and inside the TCP port range.
 *
 * The production destination never uses this overload: it is constructed from
 * the non-null pinned TCP/443 policy target.
 */
internal fun RelayTarget(host: String, validatedTestPort: Int?): RelayTarget =
    RelayTarget(
        host = host,
        port = requireNotNull(validatedTestPort) {
            "Validated test relay port must not be null"
        },
    )
