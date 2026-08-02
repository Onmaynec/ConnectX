package dev.connectx.vpn.relay

import java.security.SecureRandom
import java.util.Base64

/** Process-scoped credentials for the loopback SOCKS5 endpoint. */
class Socks5Credentials(
    val username: String,
    val password: String,
) {
    init {
        require(username.toByteArray(Charsets.UTF_8).size in 1..255) {
            "SOCKS5 username must contain 1..255 UTF-8 bytes"
        }
        require(password.toByteArray(Charsets.UTF_8).size in 1..255) {
            "SOCKS5 password must contain 1..255 UTF-8 bytes"
        }
    }

    override fun toString(): String =
        "Socks5Credentials(username=$username, password=<redacted>)"

    companion object {
        private const val RANDOM_SECRET_BYTES = 32

        fun random(
            secureRandom: SecureRandom = SecureRandom(),
        ): Socks5Credentials {
            val secret = ByteArray(RANDOM_SECRET_BYTES).also(secureRandom::nextBytes)
            return Socks5Credentials(
                username = "connectx",
                password = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(secret),
            )
        }
    }
}
