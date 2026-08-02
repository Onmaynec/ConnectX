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

    const val MODE_FOUNDATION = "foundation"
    const val MODE_NATIVE_SELF_TEST = "native_self_test"

    const val STATUS_STARTED = "started"
    const val STATUS_STOPPED = "stopped"
    const val STATUS_ERROR = "error"
}
