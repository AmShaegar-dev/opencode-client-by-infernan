package ru.opencode.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import ru.opencode.mobile.data.PromptAttachment
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Подготовка вложений для запроса: файл из системного выборщика превращается
 * в `data:`-URI, который OpenCode V2 принимает как ссылку на файл
 * (PNG/JPEG/GIF/WebP/PDF — медиа, текст — как текст).
 */
object Attachments {

    /** Лимит OpenCode: 20 MiB после декодирования. */
    const val MAX_BYTES = 20L * 1024 * 1024

    private val mimeByName = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "json" to "application/json",
        "jsonc" to "application/json",
        "xml" to "application/xml",
        "yml" to "text/yaml",
        "yaml" to "text/yaml",
        "toml" to "text/plain",
        "ini" to "text/plain",
        "conf" to "text/plain",
        "cfg" to "text/plain",
        "env" to "text/plain",
        "properties" to "text/plain",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "log" to "text/plain",
        "kt" to "text/plain",
        "kts" to "text/plain",
        "java" to "text/plain",
        "py" to "text/plain",
        "js" to "text/plain",
        "mjs" to "text/plain",
        "cjs" to "text/plain",
        "ts" to "text/plain",
        "tsx" to "text/plain",
        "jsx" to "text/plain",
        "go" to "text/plain",
        "rs" to "text/plain",
        "rb" to "text/plain",
        "php" to "text/plain",
        "sh" to "text/plain",
        "bash" to "text/plain",
        "zsh" to "text/plain",
        "sql" to "text/plain",
        "html" to "text/plain",
        "css" to "text/plain",
        "scss" to "text/plain",
        "c" to "text/plain",
        "h" to "text/plain",
        "cpp" to "text/plain",
        "hpp" to "text/plain",
        "cs" to "text/plain",
        "swift" to "text/plain",
        "dart" to "text/plain",
        "lua" to "text/plain",
        "r" to "text/plain",
        "pl" to "text/plain",
        "vue" to "text/plain",
        "svelte" to "text/plain",
        "gradle" to "text/plain",
        "dockerfile" to "text/plain",
        "makefile" to "text/plain"
    )

    /** Возвращает описание вложения или null, если файл прочитать не удалось. */
    fun prepare(context: Context, uri: Uri): PromptAttachment? {
        val resolver = context.contentResolver
        val name = displayName(context, uri) ?: "attachment"
        val declared = resolver.getType(uri)
        val mime = mimeFor(name, declared) ?: return null
        val bytes = readBytes(context, uri, MAX_BYTES) ?: return null
        if (bytes.isEmpty()) return null
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        // data-URL должен быть однострочным.
        val mimeValue = mime.substringBefore(';').trim()
        return PromptAttachment(
            uri = "data:$mimeValue;base64,$encoded",
            name = name,
            sizeBytes = bytes.size.toLong()
        )
    }

    /**
     * Читает файл целиком, но не больше [maxBytes]. Возвращает null при ошибке
     * или превышении лимита.
     */
    fun readBytes(context: Context, uri: Uri, maxBytes: Long = MAX_BYTES): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > maxBytes) return null
            }
            buffer.toByteArray()
        }
    }

    /**
     * Вложение, которое уже лежит на сервере. OpenCode умеет читать `file://`
     * и сам подтягивает байты, поэтому в сообщении это выглядит как обычный
     * прикреплённый файл, а не как голая строка с путём.
     */
    fun serverFile(path: String, name: String, sizeBytes: Long = 0L): PromptAttachment =
        PromptAttachment(
            uri = "file://" + Uri.encode(path, "/"),
            name = name,
            sizeBytes = sizeBytes
        )

    /** MIME только для типов, которые OpenCode умеет принимать. */
    private fun mimeFor(name: String, declared: String?): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        mimeByName[extension]?.let { return it }
        val type = declared?.substringBefore(';')?.trim().orEmpty()
        if (type.startsWith("image/") || type == "application/pdf") return type
        if (type.startsWith("text/") || type == "application/json" || type == "application/xml") return type
        return null
    }

    fun isImage(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in setOf("png", "jpg", "jpeg", "gif", "webp")
    }

    /** Размер файла из content-URI, если система его сообщает. */
    fun sizeOf(context: Context, uri: Uri): Long? {
        val resolver = context.contentResolver
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index).takeIf { it >= 0 }
                }
            }
        }
        return runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0 }
            }
        }.getOrNull()
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 * 1024 ->
            String.format(Locale.US, "%.2f ТБ", bytes / 1024.0 / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.US, "%.1f ГБ", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f МБ", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} КБ"
        else -> "$bytes Б"
    }

    fun displayName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) it.getString(index) else null
        }
    }
}
