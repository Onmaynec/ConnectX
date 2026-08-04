package dev.connectx.strategy.api

import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/**
 * A manually supplied target for the restricted-network evidence lab.
 *
 * The target is deliberately limited to one normalized hostname and TCP/443.
 * Resolution is performed once by the caller and the exact selected IPv4
 * address must be reused by the protected relay socket.
 */
data class ExternalTlsEvidenceTarget private constructor(
    val hostname: String,
    val ipv4Address: String,
    val port: Int,
) {
    companion object {
        const val TLS_PORT: Int = 443

        fun validateHostname(rawHostname: String): HostnameValidationResult {
            val trimmed = rawHostname.trim().removeSuffix(".")
            if (trimmed.isEmpty()) {
                return HostnameValidationResult.Rejected(TargetRejectionReason.EMPTY_HOSTNAME)
            }
            if (
                trimmed.any(Char::isWhitespace) ||
                trimmed.contains("://") ||
                trimmed.any { it == '/' || it == '\\' || it == '@' || it == ':' }
            ) {
                return HostnameValidationResult.Rejected(TargetRejectionReason.INVALID_HOSTNAME_SYNTAX)
            }
            if (IPV4_LITERAL.matches(trimmed)) {
                return HostnameValidationResult.Rejected(TargetRejectionReason.IP_LITERAL_NOT_ALLOWED)
            }

            val ascii = try {
                IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES)
                    .lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                return HostnameValidationResult.Rejected(TargetRejectionReason.INVALID_HOSTNAME_SYNTAX)
            }

            if (ascii.length !in 1..MAX_HOSTNAME_LENGTH || '.' !in ascii) {
                return HostnameValidationResult.Rejected(TargetRejectionReason.INVALID_HOSTNAME_SYNTAX)
            }
            val labels = ascii.split('.')
            if (
                labels.any { label ->
                    label.length !in 1..MAX_LABEL_LENGTH ||
                        label.startsWith('-') ||
                        label.endsWith('-') ||
                        !LABEL.matches(label)
                }
            ) {
                return HostnameValidationResult.Rejected(TargetRejectionReason.INVALID_HOSTNAME_SYNTAX)
            }
            if (RESERVED_SUFFIXES.any { suffix -> ascii == suffix || ascii.endsWith(".$suffix") }) {
                return HostnameValidationResult.Rejected(TargetRejectionReason.RESERVED_HOSTNAME)
            }

            return HostnameValidationResult.Valid(ascii)
        }

        fun bindResolvedAddresses(
            normalizedHostname: String,
            addresses: List<InetAddress>,
        ): TargetResolutionResult {
            if (addresses.isEmpty()) {
                return TargetResolutionResult.Rejected(TargetRejectionReason.NO_RESOLVED_ADDRESS)
            }

            val ipv4Addresses = addresses.filterIsInstance<Inet4Address>()
            if (ipv4Addresses.isEmpty()) {
                return TargetResolutionResult.Rejected(TargetRejectionReason.IPV4_REQUIRED)
            }
            if (ipv4Addresses.any { address -> !PublicIpv4Policy.isAllowed(address.address) }) {
                return TargetResolutionResult.Rejected(TargetRejectionReason.NON_PUBLIC_ADDRESS)
            }

            val selected = ipv4Addresses
                .distinctBy { it.hostAddress }
                .sortedWith(
                    compareBy(
                        { unsigned(it.address[0]) },
                        { unsigned(it.address[1]) },
                        { unsigned(it.address[2]) },
                        { unsigned(it.address[3]) },
                    ),
                )
                .first()

            return TargetResolutionResult.Valid(
                ExternalTlsEvidenceTarget(
                    hostname = normalizedHostname,
                    ipv4Address = selected.hostAddress
                        ?: return TargetResolutionResult.Rejected(
                            TargetRejectionReason.NO_RESOLVED_ADDRESS,
                        ),
                    port = TLS_PORT,
                ),
            )
        }

        private fun unsigned(value: Byte): Int = value.toInt() and 0xff

        private const val MAX_HOSTNAME_LENGTH = 253
        private const val MAX_LABEL_LENGTH = 63
        private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
        private val IPV4_LITERAL = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        private val RESERVED_SUFFIXES = setOf(
            "localhost",
            "local",
            "internal",
            "invalid",
            "test",
            "example",
            "home.arpa",
        )
    }
}

sealed interface HostnameValidationResult {
    data class Valid(val normalizedHostname: String) : HostnameValidationResult
    data class Rejected(val reason: TargetRejectionReason) : HostnameValidationResult
}

sealed interface TargetResolutionResult {
    data class Valid(val target: ExternalTlsEvidenceTarget) : TargetResolutionResult
    data class Rejected(val reason: TargetRejectionReason) : TargetResolutionResult
}

enum class TargetRejectionReason {
    EMPTY_HOSTNAME,
    INVALID_HOSTNAME_SYNTAX,
    IP_LITERAL_NOT_ALLOWED,
    RESERVED_HOSTNAME,
    NO_RESOLVED_ADDRESS,
    IPV4_REQUIRED,
    NON_PUBLIC_ADDRESS,
}

/** Blocks non-global and special-purpose IPv4 destinations. */
object PublicIpv4Policy {
    fun isAllowed(address: ByteArray): Boolean {
        if (address.size != 4) return false
        return BLOCKED_RANGES.none { range -> range.contains(address) }
    }

    private data class Cidr(
        val network: Long,
        val prefixLength: Int,
    ) {
        private val mask: Long = if (prefixLength == 0) {
            0L
        } else {
            (0xffff_ffffL shl (32 - prefixLength)) and 0xffff_ffffL
        }

        fun contains(address: ByteArray): Boolean =
            (toLong(address) and mask) == (network and mask)
    }

    private fun cidr(a: Int, b: Int, c: Int, d: Int, prefix: Int): Cidr =
        Cidr(
            network = ((a.toLong() shl 24) or
                (b.toLong() shl 16) or
                (c.toLong() shl 8) or
                d.toLong()) and 0xffff_ffffL,
            prefixLength = prefix,
        )

    private fun toLong(address: ByteArray): Long =
        ((address[0].toLong() and 0xff) shl 24) or
            ((address[1].toLong() and 0xff) shl 16) or
            ((address[2].toLong() and 0xff) shl 8) or
            (address[3].toLong() and 0xff)

    private val BLOCKED_RANGES = listOf(
        cidr(0, 0, 0, 0, 8),
        cidr(10, 0, 0, 0, 8),
        cidr(100, 64, 0, 0, 10),
        cidr(127, 0, 0, 0, 8),
        cidr(169, 254, 0, 0, 16),
        cidr(172, 16, 0, 0, 12),
        cidr(192, 0, 0, 0, 24),
        cidr(192, 0, 2, 0, 24),
        cidr(192, 88, 99, 0, 24),
        cidr(192, 168, 0, 0, 16),
        cidr(198, 18, 0, 0, 15),
        cidr(198, 51, 100, 0, 24),
        cidr(203, 0, 113, 0, 24),
        cidr(224, 0, 0, 0, 4),
        cidr(240, 0, 0, 0, 4),
    )
}
