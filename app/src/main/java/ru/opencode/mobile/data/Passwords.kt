package ru.opencode.mobile.data

import java.security.SecureRandom
import java.util.UUID

object Passwords {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    private val random = SecureRandom()

    fun generate(length: Int = 32): String = buildString {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun isValidServerPassword(value: String): Boolean =
        value.length in 6..128 && value.all { char ->
            // Только ASCII: сервер OpenCode не принимает кириллицу в пароле API.
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-'
        }
}
