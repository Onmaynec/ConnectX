package dev.connectx.vpn.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class Socks5ConnectRequest(
    val host: String,
    val port: Int,
)

internal object Socks5Protocol {
    const val REPLY_SUCCEEDED = 0x00
    const val REPLY_GENERAL_FAILURE = 0x01
    const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
    const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

    private const val VERSION = 0x05
    private const val AUTH_USERNAME_PASSWORD = 0x02
    private const val AUTH_NO_ACCEPTABLE_METHODS = 0xFF
    private const val USERNAME_PASSWORD_VERSION = 0x01
    private const val USERNAME_PASSWORD_SUCCESS = 0x00
    private const val USERNAME_PASSWORD_FAILURE = 0x01
    private const val COMMAND_CONNECT = 0x01
    private const val ADDRESS_IPV4 = 0x01
    private const val ADDRESS_DOMAIN = 0x03
    private const val ADDRESS_IPV6 = 0x04

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

    fun readConnectRequest(input: DataInputStream): Socks5ConnectRequest {
        requireByte(input, VERSION, "Неподдерживаемая версия SOCKS-запроса")
        val command = input.readUnsignedByteOrThrow()
        input.readUnsignedByteOrThrow() // Reserved byte.

        if (command != COMMAND_CONNECT) {
            throw UnsupportedCommandException(command)
        }

        val host = when (val addressType = input.readUnsignedByteOrThrow()) {
            ADDRESS_IPV4 -> {
                InetAddress.getByAddress(input.readExactBytes(4)).hostAddress
                    ?: throw IOException("Не удалось разобрать IPv4-адрес")
            }

            ADDRESS_DOMAIN -> {
                val length = input.readUnsignedByteOrThrow()
                if (length == 0) {
                    throw IOException("Пустое доменное имя в SOCKS-запросе")
                }
                String(
                    input.readExactBytes(length),
                    StandardCharsets.US_ASCII,
                )
            }

            ADDRESS_IPV6 -> {
                InetAddress.getByAddress(input.readExactBytes(16)).hostAddress
                    ?: throw IOException("Не удалось разобрать IPv6-адрес")
            }

            else -> throw UnsupportedAddressTypeException(addressType)
        }

        val port = (input.readUnsignedByteOrThrow() shl 8) or input.readUnsignedByteOrThrow()
        if (port !in 1..65535) {
            throw IOException("Некорректный порт назначения")
        }

        return Socks5ConnectRequest(host = host, port = port)
    }

    fun writeReply(
        output: DataOutputStream,
        replyCode: Int,
    ) {
        output.write(
            byteArrayOf(
                VERSION.toByte(),
                replyCode.toByte(),
                0x00,
                ADDRESS_IPV4.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            ),
        )
        output.flush()
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
