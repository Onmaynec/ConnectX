package dev.connectx.vpn.api

object TunnelContract {
    const val ACTION_START = "dev.connectx.action.START_LOCAL_TUNNEL"
    const val ACTION_STOP = "dev.connectx.action.STOP_LOCAL_TUNNEL"
    const val ACTION_STATUS = "dev.connectx.action.LOCAL_TUNNEL_STATUS"

    const val EXTRA_STATUS = "dev.connectx.extra.TUNNEL_STATUS"
    const val EXTRA_ERROR = "dev.connectx.extra.TUNNEL_ERROR"

    const val STATUS_STARTED = "started"
    const val STATUS_STOPPED = "stopped"
    const val STATUS_ERROR = "error"
}
