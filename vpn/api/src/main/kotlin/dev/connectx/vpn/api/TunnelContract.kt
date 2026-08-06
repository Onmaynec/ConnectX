package dev.connectx.vpn.api

object TunnelContract {
    const val ACTION_START = "dev.connectx.action.START_LOCAL_TUNNEL"
    const val ACTION_STOP = "dev.connectx.action.STOP_LOCAL_TUNNEL"
    const val ACTION_STATUS = "dev.connectx.action.LOCAL_TUNNEL_STATUS"

    const val EXTRA_STATUS = "dev.connectx.extra.TUNNEL_STATUS"
    const val EXTRA_ERROR = "dev.connectx.extra.TUNNEL_ERROR"
    const val EXTRA_ENGINE_MODE = "dev.connectx.extra.ENGINE_MODE"
    const val EXTRA_NATIVE_VERSION = "dev.connectx.extra.NATIVE_VERSION"
    const val EXTRA_NATIVE_ABI = "dev.connectx.extra.NATIVE_ABI"
    const val EXTRA_PROBE_LATENCY_MILLIS = "dev.connectx.extra.PROBE_LATENCY_MILLIS"
    const val EXTRA_PROBE_UPLOADED_BYTES = "dev.connectx.extra.PROBE_UPLOADED_BYTES"
    const val EXTRA_PROBE_DOWNLOADED_BYTES = "dev.connectx.extra.PROBE_DOWNLOADED_BYTES"
    const val EXTRA_PROBE_RELAY_CONNECTIONS = "dev.connectx.extra.PROBE_RELAY_CONNECTIONS"
    const val EXTRA_PROBE_RELAY_ASSOCIATIONS = "dev.connectx.extra.PROBE_RELAY_ASSOCIATIONS"
    const val EXTRA_PROBE_DATAGRAMS = "dev.connectx.extra.PROBE_DATAGRAMS"
    const val EXTRA_PROBE_DNS_QUERIES = "dev.connectx.extra.PROBE_DNS_QUERIES"
    const val EXTRA_PROBE_DNS_RESPONSES = "dev.connectx.extra.PROBE_DNS_RESPONSES"
    const val EXTRA_PROBE_DNS_ANSWER = "dev.connectx.extra.PROBE_DNS_ANSWER"
    const val EXTRA_STRATEGY_ID = "dev.connectx.extra.STRATEGY_ID"
    const val EXTRA_STRATEGY_SEGMENTS = "dev.connectx.extra.STRATEGY_SEGMENTS"
    const val EXTRA_STRATEGY_SPLIT_OFFSET = "dev.connectx.extra.STRATEGY_SPLIT_OFFSET"
    const val EXTRA_STRATEGY_DECISION = "dev.connectx.extra.STRATEGY_DECISION"
    const val EXTRA_STRATEGY_REASON = "dev.connectx.extra.STRATEGY_REASON"
    const val EXTRA_STRATEGY_BASELINE_LATENCY_MILLIS =
        "dev.connectx.extra.STRATEGY_BASELINE_LATENCY_MILLIS"
    const val EXTRA_STRATEGY_LATENCY_MILLIS =
        "dev.connectx.extra.STRATEGY_LATENCY_MILLIS"
    const val EXTRA_STRATEGY_RECOVERY_LATENCY_MILLIS =
        "dev.connectx.extra.STRATEGY_RECOVERY_LATENCY_MILLIS"
    const val EXTRA_STRATEGY_LATENCY_DELTA_MILLIS =
        "dev.connectx.extra.STRATEGY_LATENCY_DELTA_MILLIS"
    const val EXTRA_STRATEGY_ALLOWED_LATENCY_MILLIS =
        "dev.connectx.extra.STRATEGY_ALLOWED_LATENCY_MILLIS"
    const val EXTRA_STRATEGY_BASELINE_FAILURE =
        "dev.connectx.extra.STRATEGY_BASELINE_FAILURE"
    const val EXTRA_STRATEGY_PHASE_FAILURE =
        "dev.connectx.extra.STRATEGY_PHASE_FAILURE"
    const val EXTRA_STRATEGY_RECOVERY_FAILURE =
        "dev.connectx.extra.STRATEGY_RECOVERY_FAILURE"
    const val EXTRA_STRATEGY_GATE_STATE = "dev.connectx.extra.STRATEGY_GATE_STATE"
    const val EXTRA_STRATEGY_COOLDOWN_UNTIL_MILLIS =
        "dev.connectx.extra.STRATEGY_COOLDOWN_UNTIL_MILLIS"
    const val EXTRA_EVIDENCE_HOSTNAME = "dev.connectx.extra.EVIDENCE_HOSTNAME"
    const val EXTRA_EVIDENCE_RESOLVED_IPV4 = "dev.connectx.extra.EVIDENCE_RESOLVED_IPV4"
    const val EXTRA_EVIDENCE_TARGET_PORT = "dev.connectx.extra.EVIDENCE_TARGET_PORT"
    const val EXTRA_EVIDENCE_BASELINE_RECORD_KIND =
        "dev.connectx.extra.EVIDENCE_BASELINE_RECORD_KIND"
    const val EXTRA_EVIDENCE_STRATEGY_RECORD_KIND =
        "dev.connectx.extra.EVIDENCE_STRATEGY_RECORD_KIND"
    const val EXTRA_EVIDENCE_RECOVERY_RECORD_KIND =
        "dev.connectx.extra.EVIDENCE_RECOVERY_RECORD_KIND"
    const val EXTRA_EVIDENCE_BASELINE_SUCCESSES =
        "dev.connectx.extra.EVIDENCE_BASELINE_SUCCESSES"
    const val EXTRA_EVIDENCE_BASELINE_FAILURES =
        "dev.connectx.extra.EVIDENCE_BASELINE_FAILURES"
    const val EXTRA_EVIDENCE_STRATEGY_SUCCESSES =
        "dev.connectx.extra.EVIDENCE_STRATEGY_SUCCESSES"
    const val EXTRA_EVIDENCE_STRATEGY_FAILURES =
        "dev.connectx.extra.EVIDENCE_STRATEGY_FAILURES"
    const val EXTRA_EVIDENCE_RECOVERY_SUCCESSES =
        "dev.connectx.extra.EVIDENCE_RECOVERY_SUCCESSES"
    const val EXTRA_EVIDENCE_RECOVERY_FAILURES =
        "dev.connectx.extra.EVIDENCE_RECOVERY_FAILURES"
    const val EXTRA_EVIDENCE_FD_BEFORE =
        "dev.connectx.extra.EVIDENCE_FD_BEFORE"
    const val EXTRA_EVIDENCE_FD_AFTER =
        "dev.connectx.extra.EVIDENCE_FD_AFTER"
    const val EXTRA_EVIDENCE_FD_DELTA =
        "dev.connectx.extra.EVIDENCE_FD_DELTA"

    // Honored only when the installed application is debuggable. Release builds
    // reject these values before opening any relay or TUN resource.
    const val EXTRA_EVIDENCE_TEST_RESOLVED_IPV4 =
        "dev.connectx.extra.EVIDENCE_TEST_RESOLVED_IPV4"
    const val EXTRA_EVIDENCE_TEST_LOOPBACK_PORT =
        "dev.connectx.extra.EVIDENCE_TEST_LOOPBACK_PORT"

    const val MODE_FOUNDATION = "foundation"
    const val MODE_NATIVE_SELF_TEST = "native_self_test"
    const val MODE_NATIVE_TCP_PROBE = "native_tcp_probe"
    const val MODE_NATIVE_UDP_PROBE = "native_udp_probe"
    const val MODE_NATIVE_DNS_PROBE = "native_dns_probe"
    const val MODE_NATIVE_TLS_SPLIT_PROBE = "native_tls_split_probe"
    const val MODE_NATIVE_STRATEGY_EVALUATION = "native_strategy_evaluation"
    const val MODE_NATIVE_EXTERNAL_TLS_EVIDENCE = "native_external_tls_evidence"

    const val STATUS_STARTED = "started"
    const val STATUS_STOPPED = "stopped"
    const val STATUS_PROBE_SUCCEEDED = "probe_succeeded"
    const val STATUS_UDP_PROBE_SUCCEEDED = "udp_probe_succeeded"
    const val STATUS_DNS_PROBE_SUCCEEDED = "dns_probe_succeeded"
    const val STATUS_STRATEGY_PROBE_SUCCEEDED = "strategy_probe_succeeded"
    const val STATUS_STRATEGY_EVALUATION_COMPLETED = "strategy_evaluation_completed"
    const val STATUS_EXTERNAL_TLS_EVIDENCE_COMPLETED = "external_tls_evidence_completed"
    const val STATUS_ERROR = "error"
}
