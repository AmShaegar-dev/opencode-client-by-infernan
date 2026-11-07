package ru.opencode.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.opencode.mobile.data.TextCleaner
import ru.opencode.mobile.ui.theme.Blue
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

/**
 * Небольшой Markdown-рендер для ответов модели: заголовки, списки, цитаты,
 * **жирный**, *курсив*, `код`, ~~зачёркнутый~~ и ссылки.
 * Полноценный Markdown не нужен — важно убрать «сырые» ** и ### из текста.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color = TextMain,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 20.sp
) {
    val clean = remember(text) { TextCleaner.clean(text) }
    if (clean.contains("```")) {
        Column {
            val segments = clean.split("```")
            segments.forEachIndexed { index, segment ->
                if (index % 2 == 1) {
                    val body = segment.substringAfter('\n', segment)
                    CodeBlock(body.trimEnd())
                } else if (segment.isNotBlank()) {
                    MarkdownText(segment.trim(), color, fontSize, lineHeight)
                }
                if (index != segments.lastIndex) Spacer(Modifier.height(6.dp))
            }
        }
        return
    }
    val annotated = remember(clean, color, fontSize) { renderMarkdown(clean, fontSize) }
    Text(annotated, color = color, fontSize = fontSize, lineHeight = lineHeight)
}

private val inline = Regex(
    "`([^`\\n]+)`" +
        "|\\*\\*([^*\\n]+)\\*\\*" +
        "|__([^_\\n]+)__" +
        "|~~([^~\\n]+)~~" +
        "|\\*([^*\\n]+)\\*" +
        "|_([^_\\n]+)_" +
        "|\\[([^\\]]+)]\\(([^)\\s]+)\\)"
)

private val heading = Regex("^(#{1,6})\\s+(.*)$")
private val bullet = Regex("^\\s*[-*+•]\\s+(.*)$")
private val numbered = Regex("^\\s*(\\d+[.)])\\s+(.*)$")
private val quote = Regex("^\\s*>\\s?(.*)$")
private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

private fun renderMarkdown(source: String, baseSize: TextUnit): AnnotatedString {
    val lines = source.replace("\r\n", "\n").split('\n')
    return buildAnnotatedString {
        lines.forEachIndexed { index, raw ->
            if (index > 0) append('\n')
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit
                rule.matches(line) -> {
                    val start = length
                    append("────────────")
                    addStyle(SpanStyle(color = TextMuted), start, length)
                }
                heading.matches(line) -> {
                    val match = heading.matchEntire(line)!!
                    val level = match.groupValues[1].length
                    val size = when (level) {
                        1 -> baseSize * 1.35f
                        2 -> baseSize * 1.2f
                        else -> baseSize * 1.08f
                    }
                    appendInline(match.groupValues[2], SpanStyle(fontWeight = FontWeight.Bold, fontSize = size))
                }
                bullet.matches(line) -> {
                    val match = bullet.matchEntire(line)!!
                    append("  • ")
                    appendInline(match.groupValues[1])
                }
                numbered.matches(line) -> {
                    val match = numbered.matchEntire(line)!!
                    append("  ")
                    append(match.groupValues[1])
                    append(' ')
                    appendInline(match.groupValues[2])
                }
                quote.matches(line) -> {
                    val match = quote.matchEntire(line)!!
                    append("│ ")
                    appendInline(
                        match.groupValues[1],
                        SpanStyle(color = TextMuted, fontStyle = FontStyle.Italic)
                    )
                }
                line.trimStart().startsWith("|") -> {
                    // Строки таблиц показываем моноширинно, чтобы столбцы читались.
                    append(line)
                    addStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, color = TextMuted),
                        length - line.length,
                        length
                    )
                }
                else -> appendInline(line)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String, base: SpanStyle? = null) {
    var cursor = 0
    for (match in inline.findAll(text)) {
        if (match.range.first > cursor) {
            val part = text.substring(cursor, match.range.first)
            appendStyled(part, base)
        }
        val value = match.groupValues
        when {
            value[1].isNotEmpty() -> appendStyled(
                value[1],
                base?.merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFF191920),
                        color = Color(0xFFE8C37A)
                    )
                ) ?: SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0xFF191920),
                    color = Color(0xFFE8C37A)
                )
            )
            value[2].isNotEmpty() || value[3].isNotEmpty() -> appendStyled(
                value[2].ifEmpty { value[3] },
                (base ?: SpanStyle()).merge(SpanStyle(fontWeight = FontWeight.Bold))
            )
            value[4].isNotEmpty() -> appendStyled(
                value[4],
                (base ?: SpanStyle()).merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
            )
            value[5].isNotEmpty() || value[6].isNotEmpty() -> appendStyled(
                value[5].ifEmpty { value[6] },
                (base ?: SpanStyle()).merge(SpanStyle(fontStyle = FontStyle.Italic))
            )
            else -> appendStyled(
                value[7],
                (base ?: SpanStyle()).merge(
                    SpanStyle(color = Blue, textDecoration = TextDecoration.Underline)
                )
            )
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) appendStyled(text.substring(cursor), base)
}

private fun AnnotatedString.Builder.appendStyled(text: String, style: SpanStyle?) {
    val start = length
    append(text)
    if (style != null && length > start) addStyle(style, start, length)
}
