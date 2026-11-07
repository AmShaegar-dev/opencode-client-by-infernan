package ru.opencode.mobile.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Шифрованный кэш чатов на телефоне.
 *
 * Зачем: список сессий и история переписки лежат на сервере, и на медленной
 * сети или большом чате экран открывается несколько секунд. Здесь сохраняется
 * последний удачный ответ API, чтобы показать его сразу, а затем тихо обновить
 * с сервера.
 *
 * Данные шифруются AES/GCM ключом из Android Keystore ([SecretBox]) и лежат
 * во внутреннем каталоге приложения: скопировать файлы на другое устройство
 * и прочитать их нельзя.
 *
 * Файл называется `<prefix>_<hash>_<время>.bin`; время в имени позволяет
 * удалять самые старые записи без чтения и расшифровки.
 */
class ChatCache(context: Context) {

    private val dir = File(context.filesDir, "chat-cache").apply { mkdirs() }
    private val lock = Any()
    private val lastWrite = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------
    // Список сессий
    // ------------------------------------------------------------------

    fun loadSessions(serverId: String): String? = read(PREFIX_SESSIONS, hash(serverId))

    fun saveSessions(serverId: String, raw: String) {
        write(PREFIX_SESSIONS, hash(serverId), raw)
    }

    // ------------------------------------------------------------------
    // Сообщения чата
    // ------------------------------------------------------------------

    fun loadMessages(serverId: String, sessionId: String): String? =
        read(PREFIX_MESSAGES, hash("$serverId|$sessionId"))

    /**
     * @param force true — сохранить принудительно (ручное обновление, конец
     * ответа). Иначе запись не чаще раза в [THROTTLE_MS], чтобы опрос сервера
     * во время ответа не переписывал мегабайты на диск каждую секунду.
     */
    fun saveMessages(serverId: String, sessionId: String, raw: String, force: Boolean = false) {
        val token = hash("$serverId|$sessionId")
        if (!force) {
            val previous = lastWrite[token] ?: 0L
            if (System.currentTimeMillis() - previous < THROTTLE_MS) return
        }
        write(PREFIX_MESSAGES, token, raw)
    }

    /** Полная очистка кэша (используется при выходе/смене пароля). */
    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            lastWrite.clear()
        }
    }

    // ------------------------------------------------------------------

    private fun read(prefix: String, token: String): String? {
        val file = synchronized(lock) { newest(prefix, token) } ?: return null
        return runCatching {
            runCatching { file.readText() }.getOrNull()?.let { SecretBox.decrypt(it) }
        }.getOrNull()
    }

    private fun write(prefix: String, token: String, raw: String) {
        if (raw.isBlank() || raw.length > MAX_CHARS) return
        val packed = runCatching { SecretBox.encrypt(raw) }.getOrNull() ?: return
        synchronized(lock) {
            dir.listFiles()
                ?.filter { it.name.startsWith("${prefix}_${token}_") }
                ?.forEach { it.delete() }
            val file = File(dir, "${prefix}_${token}_${System.currentTimeMillis()}.bin")
            val tmp = File(dir, file.name + ".tmp")
            runCatching {
                tmp.writeText(packed)
                if (!tmp.renameTo(file)) {
                    file.writeText(packed)
                    tmp.delete()
                }
            }.onFailure { tmp.delete() }
            lastWrite[token] = System.currentTimeMillis()
            prune()
        }
    }

    private fun newest(prefix: String, token: String): File? =
        dir.listFiles()
            ?.filter { it.name.startsWith("${prefix}_${token}_") }
            ?.maxByOrNull { it.name }

    /** Держим не больше [MAX_MESSAGES] чатов и [MAX_BYTES] на диске. */
    private fun prune() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: return
        var total = files.sumOf { it.length() }
        var messages = files.count { it.name.startsWith("${PREFIX_MESSAGES}_") }
        for (file in files) {
            if (total <= MAX_BYTES && messages <= MAX_MESSAGES) break
            if (file.name.startsWith("${PREFIX_MESSAGES}_")) messages--
            total -= file.length()
            file.delete()
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(24)

    private companion object {
        const val PREFIX_SESSIONS = "ses"
        const val PREFIX_MESSAGES = "msg"
        const val MAX_BYTES = 8L * 1024 * 1024
        const val MAX_MESSAGES = 40
        const val MAX_CHARS = 4 * 1024 * 1024
        const val THROTTLE_MS = 15_000L
    }
}
