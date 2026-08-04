package dev.connectx.strategy.api

import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLContext

/** Creates one local ClientHello record without opening a network connection. */
object TlsClientHelloFactory {
    fun create(normalizedHostname: String): TlsClientHelloCreationResult {
        val validation = ExternalTlsEvidenceTarget.validateHostname(normalizedHostname)
        if (
            validation !is HostnameValidationResult.Valid ||
            validation.normalizedHostname != normalizedHostname
        ) {
            return TlsClientHelloCreationResult.Rejected(
                TlsClientHelloCreationFailure.INVALID_HOSTNAME,
            )
        }

        return try {
            val engine = SSLContext.getDefault().createSSLEngine(
                normalizedHostname,
                ExternalTlsEvidenceTarget.TLS_PORT,
            )
            engine.useClientMode = true

            val enabledProtocols = engine.supportedProtocols
                .filter { protocol -> protocol == "TLSv1.3" || protocol == "TLSv1.2" }
                .toTypedArray()
            if (enabledProtocols.isEmpty()) {
                return TlsClientHelloCreationResult.Rejected(
                    TlsClientHelloCreationFailure.NO_SUPPORTED_TLS_PROTOCOL,
                )
            }
            engine.enabledProtocols = enabledProtocols

            val parameters = engine.sslParameters
            parameters.serverNames = listOf(SNIHostName(normalizedHostname))
            engine.sslParameters = parameters
            engine.beginHandshake()

            val packetCapacity = engine.session.packetBufferSize.coerceIn(
                MIN_PACKET_BUFFER_BYTES,
                MAX_PACKET_BUFFER_BYTES,
            )
            val output = ByteBuffer.allocate(packetCapacity)
            val result = engine.wrap(EMPTY_APPLICATION_DATA.duplicate(), output)
            if (
                result.status != SSLEngineResult.Status.OK ||
                result.bytesProduced() <= 0 ||
                result.bytesProduced() > TlsClientHelloInspector.MAX_LAB_PAYLOAD_BYTES
            ) {
                return TlsClientHelloCreationResult.Rejected(
                    TlsClientHelloCreationFailure.ENGINE_DID_NOT_PRODUCE_CLIENT_HELLO,
                )
            }

            output.flip()
            val payload = ByteArray(output.remaining())
            output.get(payload)
            when (TlsClientHelloInspector.inspect(payload)) {
                is TlsClientHelloInspector.Result.Valid ->
                    TlsClientHelloCreationResult.Created(payload)
                is TlsClientHelloInspector.Result.Invalid ->
                    TlsClientHelloCreationResult.Rejected(
                        TlsClientHelloCreationFailure.UNSUPPORTED_CLIENT_HELLO_LAYOUT,
                    )
            }
        } catch (_: Exception) {
            TlsClientHelloCreationResult.Rejected(
                TlsClientHelloCreationFailure.TLS_ENGINE_FAILURE,
            )
        }
    }

    private val EMPTY_APPLICATION_DATA: ByteBuffer = ByteBuffer.allocate(0).asReadOnlyBuffer()
    private const val MIN_PACKET_BUFFER_BYTES = 4 * 1024
    private const val MAX_PACKET_BUFFER_BYTES = 32 * 1024
}

sealed interface TlsClientHelloCreationResult {
    class Created(payload: ByteArray) : TlsClientHelloCreationResult {
        private val storedPayload = payload.copyOf()

        val payload: ByteArray
            get() = storedPayload.copyOf()

        init {
            require(storedPayload.isNotEmpty())
            require(storedPayload.size <= TlsClientHelloInspector.MAX_LAB_PAYLOAD_BYTES)
        }
    }

    data class Rejected(
        val reason: TlsClientHelloCreationFailure,
    ) : TlsClientHelloCreationResult
}

enum class TlsClientHelloCreationFailure {
    INVALID_HOSTNAME,
    NO_SUPPORTED_TLS_PROTOCOL,
    ENGINE_DID_NOT_PRODUCE_CLIENT_HELLO,
    UNSUPPORTED_CLIENT_HELLO_LAYOUT,
    TLS_ENGINE_FAILURE,
}
