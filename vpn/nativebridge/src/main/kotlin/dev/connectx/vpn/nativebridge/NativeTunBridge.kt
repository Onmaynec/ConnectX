package dev.connectx.vpn.nativebridge

import android.os.ParcelFileDescriptor
import java.io.Closeable

object NativeBridgeFeature {
    /** Enabled only after real TUN lifecycle tests pass on physical devices. */
    const val ENABLED_BY_DEFAULT: Boolean = false
}

data class NativeBridgeSmokeReport(
    val version: String,
    val invalidStartCode: Int,
    val invalidStartError: String,
    val stopCode: Int,
    val runningAfterStop: Boolean,
)

class NativeTunBridge private constructor() {
    companion object {
        const val CODE_OK = 0
        const val CODE_INVALID_INPUT = 1

        private val loadFailure: Throwable? = runCatching {
            System.loadLibrary("connectxbridge")
        }.exceptionOrNull()

        fun isAvailable(): Boolean = loadFailure == null

        fun loadError(): String? = loadFailure?.message

        fun version(): Result<String> = runCatching {
            requireLoaded()
            nativeVersion()
        }

        /**
         * Runs a side-effect-bounded JNI smoke test without creating a TUN.
         *
         * The invalid descriptor path must return a controlled error, and stop
         * must remain idempotent. This is safe to run in Android instrumentation.
         */
        fun runtimeSmokeTest(): Result<NativeBridgeSmokeReport> = runCatching {
            requireLoaded()
            check(!nativeIsRunning()) { "Native bridge was already running before smoke test" }

            val invalidStartCode = nativeStart(
                tunFd = -1,
                mtu = 1500,
                host = "127.0.0.1",
                port = 1,
                username = "connectx-smoke",
                password = "not-a-secret",
            )
            val invalidStartError = nativeLastError()
            val stopCode = nativeStop()
            val runningAfterStop = nativeIsRunning()

            NativeBridgeSmokeReport(
                version = nativeVersion(),
                invalidStartCode = invalidStartCode,
                invalidStartError = invalidStartError,
                stopCode = stopCode,
                runningAfterStop = runningAfterStop,
            )
        }

        internal fun requireAvailable() {
            requireLoaded()
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
 * Go. Native code closes that descriptor on every normal success or failure
 * return. Kotlin reclaims it only when the JNI call itself fails before the
 * native ownership contract can complete.
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
        NativeTunBridge.requireAvailable()

        val duplicate = ParcelFileDescriptor.dup(tunnel.fileDescriptor)
        val ownedFd = duplicate.detachFd()
        val result = try {
            NativeTunBridge.start(
                ownedTunFd = ownedFd,
                mtu = mtu,
                host = relayHost,
                port = relayPort,
                username = relayUsername,
                password = relayPassword,
            )
        } catch (error: Throwable) {
            closeDetachedFd(ownedFd)
            throw error
        }

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

    private fun closeDetachedFd(fd: Int) {
        runCatching {
            ParcelFileDescriptor.adoptFd(fd).close()
        }
    }
}
