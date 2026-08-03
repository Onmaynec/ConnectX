package dev.connectx.vpn.relay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class Socks5Request(
    val command: Int,
    val host: String,
    val port: Int,
)

internal data class Socks5ConnectRequest(
    val host: String,
    val port: Int,
)

internal data class Socks5UdpDatagram(
    val target: RelayTarget,
    val payload: ByteArray,
)

internal object Socks5Protocol {
    const val REPLY_SUCCEEDED = 0x00
    const val REPLY_GENERAL_FAILURE = 0x01
    const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
    const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

    const val COMMAND_CONNECT = 0x01
    const val COMMAND_UDP_ASSOCIATE = 0x03

    private const val VERSION = 0x05
    private const val AUTH_USERNAME_PASSWORD = 0x02
    private const val AUTH_NO_ACCEPTABLE_METHODS = 0xFF
    private const val USERNAME_PASSWORD_VERSION = 0x01
    private const val USERNAME_PASSWORD_SUCCESS = 0x00
    private const val USERNAME_PASSWORD_FAILURE = 0x01
    private const val ADDRESS_IPV4 = 0x01
    private const val ADDRESS_DOMAIN = 0x03
    private const val ADDRESS_IPV6 = 0x04
    private const val MAX_UDP_PAYLOAD_BYTES = 65_507

    fun authenticateClient(
        input: DataInputStream,
        output: DataOutputStream,
        credentials: Socks5Credentials,
    ) {
        requireByte(input, VERSION, "Неподдерживаемая версия SOCKS")
        val methodCount = input.readUnsignedByteOrThrow()
        if (methodCount == 0) {
            throw IOException("SOCKS-клиент не предложил метод авторизации")
        }

        var supportsUsernamePassword = false
        repeat(methodCount) {
            if (input.readUnsignedByteOrThrow() == AUTH_USERNAME_PASSWORD) {
                supportsUsernamePassword = true
            }
        }

        output.writeByte(VERSION)
        output.writeByte(
            if (supportsUsernamePassword) {
                AUTH_USERNAME_PASSWORD
            } else {
                AUTH_NO_ACCEPTABLE_METHODS
            },
        )
        output.flush()

        if (!supportsUsernamePassword) {
            throw IOException("SOCKS-клиент не поддерживает обязательную локальную авторизацию")
        }

        requireByte(
            input = input,
            expected = USERNAME_PASSWORD_VERSION,
            message = "Неподдерживаемая версия SOCKS-аутентификации",
        )

        val usernameLength = input.readUnsignedByteOrThrow()
        if (usernameLength == 0) {
            rejectCredentials(output)
            throw IOException("Пустое имя пользователя SOCKS")
        }
        val suppliedUsername = input.readExactBytes(usernameLength)

        val passwordLength = input.readUnsignedByteOrThrow()
        if (passwordLength == 0) {
            rejectCredentials(output)
            throw IOException("Пустой пароль SOCKS")
        }
        val suppliedPassword = input.readExactBytes(passwordLength)

        val expectedUsername = credentials.username.toByteArray(StandardCharsets.UTF_8)
        val expectedPassword = credentials.password.toByteArray(StandardCharsets.UTF_8)
        val credentialsMatch = MessageDigest.isEqual(
            suppliedUsername,
            expectedUsername,
        ) && MessageDigest.isEqual(
            suppliedPassword,
            expectedPassword,
        )

        output.writeByte(USERNAME_PASSWORD_VERSION)
        output.writeByte(
            if (credentialsMatch) {
                USERNAME_PASSWORD_SUCCESS
            } else {
                USERNAME_PASSWORD_FAILURE
            },
        )
        output.flush()

        if (!credentialsMatch) {
            throw IOException("Неверные учётные данные локального SOCKS-клиента")
        }
    }

    fun readRequest(input: DataInputStream): Socks5Request {
        requireByte(input, VERSION, "Неподдерживаемая версия SOCKS-запроса")
        val command = input.readUnsignedByteOrThrow()
        input.readUnsignedByteOrThrow() // Reserved byte.
        val host = readAddress(
            addressType = input.readUnsignedByteOrThrow(),
            readByte = { input.readUnsignedByteOrThrow() },
            readBytes = { count -> input.readExactBytes(count) },
        )
        val port = (input.readUnsignedByteOrThrow() shl 8) or input.readUnsignedByteOrThrow()

        when (command) {
            COMMAND_CONNECT -> if (port !in 1..65535) {
                throw IOException("Некорректный порт назначения")
            }

            COMMAND_UDP_ASSOCIATE -> {
                if (port !in 0..65535) {
                    throw IOException("Некорректный порт UDP association")
                }
                UdpProbeTrace.onAssociateRequest()
            }

            else -> throw UnsupportedCommandException(command)
        }

        return Socks5Request(command = command, host = host, port = port)
    }

    fun readConnectRequest(input: DataInputStream): Socks5ConnectRequest {
        val request = readRequest(input)
        if (request.command != COMMAND_CONNECT) {
            throw UnsupportedCommandException(request.command)
        }
        return Socks5ConnectRequest(host = request.host, port = request.port)
    }

    fun writeReply(
        output: DataOutputStream,
        replyCode: Int,
        bindHost: String = "0.0.0.0",
        bindPort: Int = 0,
    ) {
        require(bindPort in 0..65535)
        val bindAddress = parseNumericIpv4(bindHost)
            ?: throw IOException("SOCKS reply requires a numeric IPv4 bind address")

        output.writeByte(VERSION)
        output.writeByte(replyCode)
        output.writeByte(0x00)
        output.writeByte(ADDRESS_IPV4)
        output.write(bindAddress)
        output.writeByte((bindPort ushr 8) and 0xFF)
        output.writeByte(bindPort and 0xFF)
        output.flush()

        if (replyCode == REPLY_SUCCEEDED && bindPort > 0) {
            UdpProbeTrace.onAssociationReady()
        }
    }

    fun decodeUdpDatagram(
        bytes: ByteArray,
        length: Int = bytes.size,
    ): Socks5UdpDatagram {
        UdpProbeTrace.onRelayPacketReceived()
        if (length !in 4..bytes.size) {
            throw IOException("Некорректная длина SOCKS5 UDP datagram")
        }
        var cursor = 0

        fun readByte(): Int {
            if (cursor >= length) throw IOException("Обрезанная SOCKS5 UDP datagram")
            return bytes[cursor++].toInt() and 0xFF
        }

        fun readBytes(count: Int): ByteArray {
            if (count < 0 || cursor + count > length) {
                throw IOException("Обрезанный адрес SOCKS5 UDP datagram")
            }
            return bytes.copyOfRange(cursor, cursor + count).also { cursor += count }
        }

        if (readByte() != 0 || readByte() != 0) {
            throw IOException("Некорректные reserved bytes SOCKS5 UDP datagram")
        }
        val fragment = readByte()
        if (fragment != 0) {
            throw UnsupportedUdpFragmentException(fragment)
        }

        val host = readAddress(
            addressType = readByte(),
            readByte = ::readByte,
            readBytes = ::readBytes,
        )
        val port = (readByte() shl 8) or readByte()
        if (port !in 1..65535) {
            throw IOException("Некорректный UDP target port")
        }

        return Socks5UdpDatagram(
            target = RelayTarget(host = host, port = port),
            payload = readBytes(length - cursor),
        )
    }

    fun encodeUdpDatagram(
        target: RelayTarget,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size <= MAX_UDP_PAYLOAD_BYTES)
        val output = ByteArrayOutputStream(payload.size + 32)
        output.write(0)
        output.write(0)
        output.write(0) // FRAG=0; fragmentation is intentionally unsupported.
        writeAddress(output, target.host)
        output.write((target.port ushr 8) and 0xFF)
        output.write(target.port and 0xFF)
        output.write(payload)
        return output.toByteArray()
    }

    private fun readAddress(
        addressType: Int,
        readByte: () -> Int,
        readBytes: (Int) -> ByteArray,
    ): String = when (addressType) {
        ADDRESS_IPV4 -> InetAddress.getByAddress(readBytes(4)).hostAddress
            ?: throw IOException("Не удалось разобрать IPv4-адрес")

        ADDRESS_DOMAIN -> {
            val count = readByte()
            if (count == 0) throw IOException("Пустое доменное имя в SOCKS-запросе")
            String(readBytes(count), StandardCharsets.US_ASCII)
        }

        ADDRESS_IPV6 -> InetAddress.getByAddress(readBytes(16)).hostAddress
            ?: throw IOException("Не удалось разобрать IPv6-адрес")

        else -> throw UnsupportedAddressTypeException(addressType)
    }

    private fun writeAddress(output: ByteArrayOutputStream, host: String) {
        val ipv4 = parseNumericIpv4(host)
        if (ipv4 != null) {
            output.write(ADDRESS_IPV4)
            output.write(ipv4)
            return
        }

        if (host.contains(':')) {
            val ipv6 = runCatching { InetAddress.getByName(host).address }.getOrNull()
            if (ipv6?.size == 16) {
                output.write(ADDRESS_IPV6)
                output.write(ipv6)
                return
            }
        }

        val domain = host.toByteArray(StandardCharsets.US_ASCII)
        if (domain.isEmpty() || domain.size > 255) {
            throw IOException("Некорректный SOCKS domain target")
        }
        output.write(ADDRESS_DOMAIN)
        output.write(domain.size)
        output.write(domain)
    }

    private fun parseNumericIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val values = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return ByteArray(4) { index -> values[index].toByte() }
    }

    private fun rejectCredentials(output: DataOutputStream) {
        output.writeByte(USERNAME_PASSWORD_VERSION)
        output.writeByte(USERNAME_PASSWORD_FAILURE)
        output.flush()
    }

    private fun requireByte(
        input: DataInputStream,
        expected: Int,
        message: String,
    ) {
        if (input.readUnsignedByteOrThrow() != expected) {
            throw IOException(message)
        }
    }

    private fun DataInputStream.readUnsignedByteOrThrow(): Int = try {
        readUnsignedByte()
    } catch (error: EOFException) {
        throw IOException("Неожиданный конец SOCKS-запроса", error)
    }

    private fun DataInputStream.readExactBytes(count: Int): ByteArray {
        val result = ByteArray(count)
        try {
            readFully(result)
        } catch (error: EOFException) {
            throw IOException("Неожиданный конец SOCKS-адреса", error)
        }
        return result
    }
}

internal class UnsupportedCommandException(
    val command: Int,
) : IOException("SOCKS-команда $command пока не поддерживается")

internal class UnsupportedAddressTypeException(
    val addressType: Int,
) : IOException("Тип SOCKS-адреса $addressType не поддерживается")

internal class UnsupportedUdpFragmentException(
    val fragment: Int,
) : IOException("SOCKS5 UDP fragmentation не поддерживается: FRAG=$fragment")
