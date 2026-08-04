package dev.connectx.strategy.api

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTlsEvidenceTargetTest {
    @Test
    fun normalizesUnicodeHostnameAndRemovesTrailingDot() {
        val result = ExternalTlsEvidenceTarget.validateHostname("  ПРИМЕР.РФ.  ")

        assertEquals(
            HostnameValidationResult.Valid("xn--e1afmkfd.xn--p1ai"),
            result,
        )
    }

    @Test
    fun rejectsUrlsIpLiteralsAndReservedNames() {
        val cases = mapOf(
            "" to TargetRejectionReason.EMPTY_HOSTNAME,
            "https://example.org" to TargetRejectionReason.INVALID_HOSTNAME_SYNTAX,
            "user@example.org" to TargetRejectionReason.INVALID_HOSTNAME_SYNTAX,
            "example.org/path" to TargetRejectionReason.INVALID_HOSTNAME_SYNTAX,
            "1.1.1.1" to TargetRejectionReason.IP_LITERAL_NOT_ALLOWED,
            "localhost" to TargetRejectionReason.INVALID_HOSTNAME_SYNTAX,
            "service.local" to TargetRejectionReason.RESERVED_HOSTNAME,
            "service.internal" to TargetRejectionReason.RESERVED_HOSTNAME,
            "example.test" to TargetRejectionReason.RESERVED_HOSTNAME,
            "example.invalid" to TargetRejectionReason.RESERVED_HOSTNAME,
            "example.example" to TargetRejectionReason.RESERVED_HOSTNAME,
            "router.home.arpa" to TargetRejectionReason.RESERVED_HOSTNAME,
        )

        cases.forEach { (input, expectedReason) ->
            assertEquals(
                "Unexpected validation result for $input",
                HostnameValidationResult.Rejected(expectedReason),
                ExternalTlsEvidenceTarget.validateHostname(input),
            )
        }
    }

    @Test
    fun rejectsMalformedLabelsAndSingleLabelTargets() {
        listOf(
            "singlelabel",
            "-bad.example",
            "bad-.example",
            "bad_label.example",
            "two..dots.example",
            ".leading.example",
        ).forEach { input ->
            assertEquals(
                HostnameValidationResult.Rejected(
                    TargetRejectionReason.INVALID_HOSTNAME_SYNTAX,
                ),
                ExternalTlsEvidenceTarget.validateHostname(input),
            )
        }
    }

    @Test
    fun bindsOneDeterministicPublicIpv4AndFixedTlsPort() {
        val result = ExternalTlsEvidenceTarget.bindResolvedAddresses(
            normalizedHostname = "example.org",
            addresses = listOf(
                ipv4("93.184.216.35"),
                ipv4("93.184.216.34"),
                ipv4("93.184.216.35"),
            ),
        )

        assertTrue(result is TargetResolutionResult.Valid)
        val target = (result as TargetResolutionResult.Valid).target
        assertEquals("example.org", target.hostname)
        assertEquals("93.184.216.34", target.ipv4Address)
        assertEquals(443, target.port)
    }

    @Test
    fun rejectsMixedPublicAndPrivateIpv4ToPreventRebinding() {
        val result = ExternalTlsEvidenceTarget.bindResolvedAddresses(
            normalizedHostname = "example.org",
            addresses = listOf(
                ipv4("93.184.216.34"),
                ipv4("127.0.0.1"),
            ),
        )

        assertEquals(
            TargetResolutionResult.Rejected(TargetRejectionReason.NON_PUBLIC_ADDRESS),
            result,
        )
    }

    @Test
    fun requiresAtLeastOneIpv4Address() {
        val result = ExternalTlsEvidenceTarget.bindResolvedAddresses(
            normalizedHostname = "example.org",
            addresses = listOf(InetAddress.getByName("2001:4860:4860::8888")),
        )

        assertEquals(
            TargetResolutionResult.Rejected(TargetRejectionReason.IPV4_REQUIRED),
            result,
        )
    }

    @Test
    fun publicIpv4PolicyBlocksSpecialPurposeRanges() {
        val blocked = listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.0.0.1",
            "192.0.2.1",
            "192.88.99.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "239.255.255.255",
            "240.0.0.1",
            "255.255.255.255",
        )
        blocked.forEach { address ->
            assertFalse(
                "$address must be blocked",
                PublicIpv4Policy.isAllowed(ipv4(address).address),
            )
        }
    }

    @Test
    fun publicIpv4PolicyAllowsOrdinaryGlobalAddresses() {
        listOf(
            "1.1.1.1",
            "8.8.8.8",
            "9.9.9.9",
            "93.184.216.34",
        ).forEach { address ->
            assertTrue(
                "$address must be allowed",
                PublicIpv4Policy.isAllowed(ipv4(address).address),
            )
        }
    }

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByAddress(
            value.split('.').map { octet -> octet.toInt().toByte() }.toByteArray(),
        ) as Inet4Address
}
