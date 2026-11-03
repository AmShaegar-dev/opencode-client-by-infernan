package ru.opencode.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Мессенджеры (Telegram и т.п.) отдают JSON-файлы как «.txt», а системный
 * выборщик файлов не показывает их при фильтре JSON. Копия во внутреннем
 * кэше с расширением .json решает проблему выбора файла вручную.
 */
object SharedFiles {

    private const val MAX_SIZE = 32L * 1024 * 1024

    fun ensureJsonName(context: Context, uri: Uri): File? = try {
        val resolver = context.contentResolver
        val original = queryName(context, uri)
        if (original == null || original.endsWith(".json", ignoreCase = true)) {
            null
        } else {
            val base = original.substringBeforeLast('.', original)
                .ifBlank { "shared" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val target = File(dir, "${base}.json")
            var total = 0L
            val stream = resolver.openInputStream(uri) ?: return null
            stream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_SIZE) return null
                        output.write(buffer, 0, read)
                    }
                }
            }
            target
        }
    } catch (e: Exception) {
        null
    }

    private fun queryName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) it.getString(index) else null
        }
    }
}
