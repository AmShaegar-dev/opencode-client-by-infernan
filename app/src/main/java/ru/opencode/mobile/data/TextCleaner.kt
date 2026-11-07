package ru.opencode.mobile.data

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Чистит текст, который приходит с сервера OpenCode.
 *
 * Зачем это нужно:
 *  - инструменты (`shell`, особенно PowerShell) возвращают вывод в кодировке
 *    консоли, из-за чего кириллица превращается в «╨Ь╨╡╨╜╤О» и «я┐╜»;
 *  - команды и логи могут содержать ANSI-последовательности цвета;
 *  - в тексте встречаются управляющие символы, которые ломают вёрстку.
 */
object TextCleaner {

    /** Цветовые и служебные ANSI-последовательности. */
    private val ansi = Regex(
        "\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^\u0007]*(?:\u0007|\u001B\\\\)|[@-_])"
    )

    /** Управляющие символы, кроме перевода строки и табуляции. */
    private val control = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    /** Невидимые маркеры направления текста и нулевой ширины. */
    private val invisible = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")

    /** Признаки «мojibake»: кириллица, закодированная как CP866/CP437. */
    private val mojibakeMarkers = Regex("[╨╤╥╙╘╒╓╫╪╬═╠╦╩╚╔╗╝║─│┐└┴┬├┼░▒▓]")

    /** «я┐╜» — это UTF-8-байты U+FFFD, прочитанные как CP866. */
    private const val fakeReplacement = "я┐╜"

    private val cp866: Charset? = runCatching { Charset.forName("IBM866") }.getOrNull()
    private val cp437: Charset? = runCatching { Charset.forName("IBM437") }.getOrNull()
    private val utf8 = Charsets.UTF_8

    /**
     * Полная очистка: ANSI, управляющие символы, восстановление кодировки.
     * Безопасна для обычного текста — если признаков порчи нет, возвращает
     * строку без изменений.
     */
    fun clean(input: String): String {
        if (input.isEmpty()) return input
        var text = ansi.replace(input, "")
        text = control.replace(text, "")
        text = invisible.replace(text, "")
        if (text.isEmpty()) return text
        text = repairMojibake(text)
        // U+FFFD — уже потерянные байты, а «я┐╜» — их CP866-представление.
        // Схлопываем в «?», чтобы не было ромбиков и трёхсимвольной каши.
        text = text.replace(fakeReplacement, "?").replace("\uFFFD", "?")
        return text
    }

    /**
     * Пробует перекодировать текст из CP866/CP437 обратно в UTF-8.
     * Применяется только если в строке есть характерные «квадратики».
     */
    fun repairMojibake(input: String): String {
        if (input.indexOf('\uFFFD') < 0 &&
            input.indexOf(fakeReplacement) < 0 &&
            !mojibakeMarkers.containsMatchIn(input)
        ) {
            return input
        }
        val variants = listOfNotNull(cp866, cp437)
            .mapNotNull { charset -> recode(input, charset) }
        return variants.maxByOrNull { score(it) }?.takeIf { score(it) > score(input) } ?: input
    }

    private fun recode(text: String, charset: Charset): String? {
        return runCatching {
            val encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer: ByteBuffer = encoder.encode(CharBuffer.wrap(text))
            val decoder = utf8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(buffer).toString()
        }.getOrNull()
    }

    /** Насколько строка похожа на нормальный текст: кириллица и буквы в плюс, ромбики в минус. */
    private fun score(text: String): Int {
        var value = 0
        for (ch in text) {
            value += when (ch) {
                in '\u0400'..'\u04FF' -> 3
                in 'a'..'z', in 'A'..'Z' -> 1
                '\uFFFD' -> -20
                in '\u2500'..'\u259F' -> -4
                else -> 0
            }
        }
        return value
    }
}
