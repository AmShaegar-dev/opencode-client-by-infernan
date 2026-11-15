package ru.opencode.mobile.data

import org.json.JSONObject

enum class FileKind { IMAGE, TEXT, BINARY }

/**
 * Файл, который модель создала или на который сослалась в ответе.
 * Показывается в чате карточкой с кнопками «Открыть» и «Скачать».
 */
data class FileRef(
    val path: String,
    val name: String,
    val kind: FileKind
)

/**
 * Достаёт файлы из ответа модели:
 *  - из текста: пути в обратных кавычках и относительные пути с расширением;
 *  - из инструментов записи (`write`, `edit`, `patch` и т.п.) — поле `path`.
 */
object FileRefs {

    private val writeTools = setOf(
        "write", "edit", "patch", "multiedit", "multi_edit", "apply_patch",
        "create_file", "createfile", "str_replace", "notebook_edit"
    )

    private val imageExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif"
    )

    private val textExtensions = setOf(
        "txt", "md", "markdown", "json", "jsonc", "yaml", "yml", "xml", "html", "htm",
        "svg", "css", "scss", "js", "jsx", "ts", "tsx", "kt", "kts", "java", "gradle",
        "properties", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "sh",
        "bash", "zsh", "ps1", "bat", "cmd", "ini", "cfg", "conf", "toml", "sql", "log",
        "csv", "tsv", "env", "gitignore", "dockerfile", "makefile"
    )

    private val backtick = Regex("`([^`\\r\\n]{2,300})`")
    private val absolutePath = Regex("[A-Za-z]:[\\\\/][^\\s`\"'<>|*?]+")
    private val relativePath = Regex(
        "(?:[A-Za-z0-9_.\\-]+[\\\\/])+[A-Za-z0-9_.\\-]+\\.[A-Za-z0-9]{1,8}"
    )

    fun fromMessage(message: ChatMessage): List<FileRef> {
        val out = LinkedHashMap<String, FileRef>()

        fun add(raw: String) {
            val path = clean(raw) ?: return
            val key = path.lowercase()
            if (out.containsKey(key)) return
            out[key] = FileRef(path, nameOf(path), kindOf(path))
        }

        // Явные ссылки в тексте ответа — самые важные.
        fromText(message.text).forEach(::add)

        // Путь, который приложение само подставляет при загрузке на сервер.
        val serverMarker = "Файл на сервере:"
        message.text.lineSequence().forEach { line ->
            val index = line.indexOf(serverMarker)
            if (index >= 0) {
                clean(line.substring(index + serverMarker.length).trim())?.let(::add)
            }
        }

        // Затем файлы, которые создавали инструменты (последние — первыми).
        message.tools.asReversed().forEach { tool ->
            if (tool.name.lowercase() !in writeTools) return@forEach
            toolPaths(tool.input).forEach(::add)
        }

        return out.values.take(8)
    }

    fun fromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()

        // В кавычках путь может содержать пробелы — берём как есть.
        backtick.findAll(text).forEach { match ->
            val value = clean(match.groupValues[1]) ?: return@forEach
            if (isPath(value, quoted = true)) result += value
        }
        // Без кавычек — только однозначные пути без пробелов.
        absolutePath.findAll(text).forEach { match ->
            clean(match.value)?.let { value -> if (isPath(value, quoted = false)) result += value }
        }
        // Из остатка убираем абсолютные пути, чтобы не поймать их хвост
        // («tmp\build\app.apk» внутри «C:\tmp\build\app.apk»).
        val withoutAbsolute = absolutePath.replace(text, " ")
        relativePath.findAll(withoutAbsolute).forEach { match ->
            clean(match.value)?.let { value -> if (isPath(value, quoted = false)) result += value }
        }
        return result.toList()
    }

    private fun toolPaths(rawInput: String): List<String> {
        if (rawInput.isBlank()) return emptyList()
        return runCatching {
            val json = JSONObject(rawInput)
            listOf("filePath", "path", "file", "target")
                .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun isPath(value: String, quoted: Boolean): Boolean {
        if (value.isBlank() || value.length > 300) return false
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return false
        if (value.contains("://")) return false
        if (value.contains('\n')) return false
        if (!quoted && value.any { it.isWhitespace() }) return false

        val windowsAbsolute = Regex("^[A-Za-z]:[\\\\/].+").matches(value)
        val unixAbsolute = value.startsWith("/")
        val hasSeparator = value.contains('/') || value.contains('\\')
        val extension = extensionOf(value)

        // Абсолютный путь — доверяем; относительный — только с расширением.
        return when {
            windowsAbsolute || unixAbsolute -> extension.isNotBlank() || quoted
            hasSeparator -> extension.isNotBlank()
            else -> false
        }
    }

    private fun clean(raw: String): String? {
        var value = raw.trim()
        value = value.removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
        value = value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>')
        if (value.isBlank() || value.length < 3 || value.length > 300) return null
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return null
        return value
    }

    fun nameOf(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        return normalized.substringAfterLast('/').ifBlank { normalized }
    }

    private fun extensionOf(path: String): String {
        val name = nameOf(path)
        return name.substringAfterLast('.', "").lowercase()
    }

    fun kindOf(path: String): FileKind {
        val extension = extensionOf(path)
        return when {
            extension in imageExtensions -> FileKind.IMAGE
            extension in textExtensions -> FileKind.TEXT
            nameOf(path).lowercase().let { it == "dockerfile" || it == "makefile" } -> FileKind.TEXT
            else -> FileKind.BINARY
        }
    }
}
