package ru.opencode.mobile.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdate(
    val version: String,
    val name: String,
    val notes: String,
    val assetUrl: String,
    val pageUrl: String,
    val publishedAt: String
)

/**
 * Проверка обновлений по последнему GitHub Release.
 *
 * Репозиторий задаётся константами ниже. Для публичного репозитория токен
 * не нужен; при отсутствии релизов или сети возвращается понятная ошибка.
 */
object UpdateChecker {

    const val GITHUB_OWNER = "inFernan"
    const val GITHUB_REPO = "opencode-client-by-infernan"
    const val PROJECT_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "opencode_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_IGNORED_VERSION = "ignored_version"
    private const val KEY_IGNORED_AT = "ignored_at"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        return now - last >= CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    fun lastChecked(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    fun shouldPrompt(context: Context, update: AppUpdate): Boolean {
        val ignored = prefs(context).getString(KEY_IGNORED_VERSION, "").orEmpty()
        val at = prefs(context).getLong(KEY_IGNORED_AT, 0L)
        if (ignored != update.version) return true
        return System.currentTimeMillis() - at >= CHECK_INTERVAL_MS
    }

    fun ignore(context: Context, update: AppUpdate) {
        prefs(context).edit()
            .putString(KEY_IGNORED_VERSION, update.version)
            .putLong(KEY_IGNORED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Возвращает обновление или null, если установлена последняя версия. */
    fun check(currentVersion: String): AppUpdate? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OpenCode-Client-by-inFernan")
            .build()
        val response = client.newCall(request).execute()
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404) {
                throw IllegalStateException("Релизы на GitHub пока не опубликованы")
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("GitHub ответил ${resp.code}")
            }
            val root = JSONObject(body)
            val tag = root.optString("tag_name").trim().ifBlank { return null }
            val version = tag.removePrefix("v").removePrefix("V")
            if (!VersionTools.isNewer(version, currentVersion)) return null
            val assetUrl = root.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        return@let asset.optString("browser_download_url")
                    }
                }
                null
            }.orEmpty()
            return AppUpdate(
                version = version,
                name = root.optString("name").ifBlank { tag },
                notes = root.optString("body"),
                assetUrl = assetUrl,
                pageUrl = root.optString("html_url").ifBlank { PROJECT_URL },
                publishedAt = root.optString("published_at")
            )
        }
    }

    fun download(url: String, target: File, onProgress: (Float?) -> Unit): File {
        if (url.isBlank()) throw IllegalStateException("В релизе нет APK-файла")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenCode-Client-by-inFernan")
            .build()
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Не удалось скачать APK: ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Пустой ответ GitHub")
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".part")
            temp.delete()
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else null)
                    }
                }
            }
            target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(1f)
            return target
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
