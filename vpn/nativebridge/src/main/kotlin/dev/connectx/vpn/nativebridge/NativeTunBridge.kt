package dev.connectx.vpn.nativebridge

import android.os.ParcelFileDescriptor
import java.io.Closeable

object NativeBridgeFeature {
    /** Enabled only after ABI loading and physical-device lifecycle tests pass. */
    const val ENABLED_BY_DEFAULT: Boolean = false
}

class NativeTunBridge private constructor() {
    companion object {
        const val CODE_OK = 0

        private val loadFailure: Throwable? = runCatching {
            System.loadLibrary("connectxbridge")
        }.exceptionOrNull()

        fun isAvailable(): Boolean = loadFailure == null

        fun loadError(): String? = loadFailure?.message

        fun version(): Result<String> = runCatching {
            requireLoaded()
            nativeVersion()
        }

        internal fun start(
            ownedTunFd: Int,
            mtu: Int,
            host: String,
            port: Int,
            username: String,
            password: String,
        ): Int {
            requireLoaded()
            return nativeStart(
                ownedTunFd,
                mtu,
                host,
                port,
                username,
                password,
            )
        }

        internal fun stop(): Int {
            requireLoaded()
            return nativeStop()
        }

        fun isRunning(): Boolean {
            requireLoaded()
            return nativeIsRunning()
        }

        fun lastError(): String {
            requireLoaded()
            return nativeLastError()
        }

        private fun requireLoaded() {
            check(loadFailure == null) {
                "ConnectX native bridge is unavailable: ${loadFailure?.message ?: "unknown error"}"
            }
        }

        @JvmStatic
        private external fun nativeVersion(): String

        @JvmStatic
        private external fun nativeStart(
            tunFd: Int,
            mtu: Int,
            host: String,
            port: Int,
            username: String,
            password: String,
        ): Int

        @JvmStatic
        private external fun nativeStop(): Int

        @JvmStatic
        private external fun nativeIsRunning(): Boolean

        @JvmStatic
        private external fun nativeLastError(): String
    }
}

/**
 * Owns one native bridge run.
 *
 * A duplicate of the Android TUN descriptor is detached and transferred to
 * Go. Native code closes that descriptor on every success or failure path.
 */
class NativeTunSession : Closeable {
    private var started = false

    @Synchronized
    fun start(
        tunnel: ParcelFileDescriptor,
        mtu: Int,
        relayHost: String,
        relayPort: Int,
        relayUsername: String,
        relayPassword: String,
    ) {
        check(!started) { "Native TUN session is already started" }

        val duplicate = ParcelFileDescriptor.dup(tunnel.fileDescriptor)
        val ownedFd = duplicate.detachFd()
        val result = NativeTunBridge.start(
            ownedTunFd = ownedFd,
            mtu = mtu,
            host = relayHost,
            port = relayPort,
            username = relayUsername,
            password = relayPassword,
        )
        check(result == NativeTunBridge.CODE_OK) {
            NativeTunBridge.lastError().ifBlank {
                "Native bridge failed with code $result"
            }
        }
        started = true
    }

    @Synchronized
    override fun close() {
        if (!started) return
        val result = NativeTunBridge.stop()
        started = false
        check(result == NativeTunBridge.CODE_OK) {
            NativeTunBridge.lastError().ifBlank {
                "Native bridge stop failed with code $result"
            }
        }
    }
}
