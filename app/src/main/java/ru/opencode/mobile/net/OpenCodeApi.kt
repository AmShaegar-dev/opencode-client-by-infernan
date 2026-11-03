package ru.opencode.mobile.net

import okhttp3.CertificatePinner
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import ru.opencode.mobile.data.AgentInfo
import ru.opencode.mobile.data.ChatJson
import ru.opencode.mobile.data.ChatMessage
import ru.opencode.mobile.data.CommandInfo
import ru.opencode.mobile.data.FileDiffInfo
import ru.opencode.mobile.data.IntegrationInfo
import ru.opencode.mobile.data.ModelInfo
import ru.opencode.mobile.data.ModelRef
import ru.opencode.mobile.data.OAuthAttemptInfo
import ru.opencode.mobile.data.ProviderConfigV2
import ru.opencode.mobile.data.ProviderInfo
import ru.opencode.mobile.data.PromptAttachment
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.data.SessionInfo
import ru.opencode.mobile.data.UsageLimitsInfo
import ru.opencode.mobile.data.UrlTools
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OpenCodeException(val status: Int, message: String) : Exception(message)

/**
 * HTTP-клиент API OpenCode V2.
 * В режиме туннеля каждый запрос сначала поднимает (или переиспользует)
 * SSH-проброс на 127.0.0.1:<локальный порт>.
 */
class OpenCodeApi(
    private val urlProvider: () -> String,
    private val password: String,
    private val client: OkHttpClient
) {

    fun info(): JSONObject = JSONObject(get("/api/info"))

    fun sessions(search: String = ""): List<SessionInfo> {
        val query = buildString {
            append("/api/session?limit=100&order=desc")
            if (search.isNotBlank()) append("&search=").append(encode(search))
        }
        val root = JSONObject(get(query))
        return ChatJson.sessions(root.optJSONArray("data") ?: JSONArray())
    }

    fun createSession(title: String? = null, model: ModelRef? = null): SessionInfo {
        val body = JSONObject().apply {
            if (!title.isNullOrBlank()) put("title", title)
            if (model != null) put("model", model.toJson())
        }
        val root = JSONObject(post("/api/session", body))
        return ChatJson.session(root.optJSONObject("data") ?: JSONObject())
    }

    fun deleteSession(sessionId: String) {
        delete("/api/session/${encode(sessionId)}")
    }

    fun renameSession(sessionId: String, title: String) {
        patch("/api/session/${encode(sessionId)}", JSONObject().put("title", title))
    }

    fun messages(sessionId: String, limit: Int = 200): List<ChatMessage> {
        val root = JSONObject(
            get("/api/session/${encode(sessionId)}/message?limit=$limit&order=desc")
        )
        val array = root.optJSONArray("data") ?: JSONArray()
        return ChatJson.messages(array).reversed()
    }

    fun prompt(sessionId: String, text: String, attachments: List<PromptAttachment> = emptyList()) {
        val body = JSONObject().put("text", text)
        if (attachments.isNotEmpty()) {
            body.put(
                "files",
                JSONArray().apply {
                    attachments.forEach { attachment ->
                        put(
                            JSONObject()
                                .put("uri", attachment.uri)
                                .put("name", attachment.name)
                        )
                    }
                }
            )
        }
        post("/api/session/${encode(sessionId)}/prompt", body)
    }

    fun interrupt(sessionId: String) {
        post("/api/session/${encode(sessionId)}/interrupt", null)
    }

    fun switchModel(sessionId: String, model: ModelRef) {
        post(
            "/api/session/${encode(sessionId)}/model",
            JSONObject().put("model", model.toJson())
        )
    }

    fun commands(): List<CommandInfo> {
        val root = JSONObject(get("/api/command"))
        return ChatJson.commands(root.optJSONArray("data") ?: JSONArray())
    }

    fun agents(): List<AgentInfo> {
        val root = JSONObject(get("/api/agent"))
        return ChatJson.agents(root.optJSONArray("data") ?: JSONArray())
    }

    fun switchAgent(sessionId: String, agentId: String) {
        post(
            "/api/session/${encode(sessionId)}/agent",
            JSONObject().put("agent", agentId)
        )
    }

    fun runCommand(sessionId: String, name: String, text: String) {
        post(
            "/api/session/${encode(sessionId)}/command",
            JSONObject().put("name", name).put("text", text)
        )
    }

    fun models(): List<ModelInfo> {
        val root = JSONObject(get("/api/model"))
        return ChatJson.models(root.optJSONArray("data") ?: JSONArray())
    }

    fun providers(): List<ProviderInfo> {
        val root = JSONObject(get("/api/provider"))
        return ChatJson.providers(root.optJSONArray("data") ?: JSONArray())
    }

    fun integrations(): List<IntegrationInfo> {
        val root = JSONObject(get("/api/integration"))
        return ChatJson.integrations(root.optJSONArray("data") ?: JSONArray())
    }

    fun integration(integrationId: String): IntegrationInfo {
        val root = JSONObject(get("/api/integration/${encode(integrationId)}"))
        return ChatJson.integration(root.optJSONObject("data") ?: JSONObject())
    }

    fun connectKey(integrationId: String, key: String, label: String? = null) {
        val body = JSONObject().put("key", key)
        if (!label.isNullOrBlank()) body.put("label", label)
        post("/api/integration/${encode(integrationId)}/connect/key", body)
    }

    /** Начинает OAuth-вход и возвращает ссылку для браузера. */
    fun startOAuth(
        integrationId: String,
        methodId: String,
        answer: JSONObject? = null,
        label: String? = null
    ): OAuthAttemptInfo {
        val body = JSONObject().put("methodID", methodId)
        if (answer != null && answer.length() > 0) body.put("answer", answer)
        if (!label.isNullOrBlank()) body.put("label", label)
        val root = JSONObject(post("/api/integration/${encode(integrationId)}/connect/oauth", body))
        return ChatJson.oauthAttempt(root.optJSONObject("data") ?: JSONObject())
    }

    /** Возвращает пару «статус, сообщение»: pending / complete / failed / expired. */
    fun oauthStatus(integrationId: String, attemptId: String): Pair<String, String> {
        val root = JSONObject(
            get("/api/integration/${encode(integrationId)}/connect/oauth/${encode(attemptId)}")
        )
        val data = root.optJSONObject("data") ?: JSONObject()
        return data.optString("status").ifBlank { "pending" } to data.optString("message")
    }

    fun completeOAuth(integrationId: String, attemptId: String, code: String?) {
        val body = JSONObject()
        if (!code.isNullOrBlank()) body.put("code", code.trim())
        post(
            "/api/integration/${encode(integrationId)}/connect/oauth/${encode(attemptId)}/complete",
            body
        )
    }

    fun cancelOAuth(integrationId: String, attemptId: String) {
        delete("/api/integration/${encode(integrationId)}/connect/oauth/${encode(attemptId)}")
    }

    fun removeCredential(credentialId: String) {
        delete("/api/credential/${encode(credentialId)}")
    }

    fun activateCredential(credentialId: String) {
        post("/api/credential/${encode(credentialId)}/activate", null)
    }

    fun reload() {
        post("/api/location/reload", null)
    }

    // ------------------------------------------------------------------
    // Конфигурация opencode.json через HTTP API (без отдельного SSH)
    // ------------------------------------------------------------------

    /**
     * Читает путь и содержимое opencode.json через серверную команду.
     * Работает и в режиме прямого URL, и через уже поднятый SSH-туннель.
     */
    fun readConfig(): Pair<String, String> {
        val path = configDocumentPath()
            ?: shell(CONFIG_PATH_SCRIPT)
                .trim()
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .ifBlank { "/root/.config/opencode/opencode.json" }
        val content = shell("cat ${shellQuote(path)} 2>/dev/null || true")
        return path to content.trim()
    }

    /**
     * Выключенные модели из конфигурации сервера (`/api/config`), без shell.
     * `/api/model` их не возвращает, поэтому список для управления берём отсюда.
     */
    fun disabledModels(): List<ProviderConfigV2.DisabledModel> {
        val text = get("/api/config")
        val array = runCatching {
            if (text.trimStart().startsWith("[")) {
                JSONArray(text)
            } else {
                JSONObject(text).optJSONArray("data")
            }
        }.getOrNull() ?: return emptyList()

        val names = LinkedHashMap<String, ProviderConfigV2.DisabledModel>()
        val disabled = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val info = array.optJSONObject(index)?.optJSONObject("info") ?: continue
            for (section in listOf("providers", "provider")) {
                val providers = info.optJSONObject(section) ?: continue
                for (providerId in providers.keys()) {
                    val models = providers.optJSONObject(providerId)?.optJSONObject("models") ?: continue
                    for (modelId in models.keys()) {
                        val model = models.optJSONObject(modelId) ?: continue
                        if (!model.has("disabled")) continue
                        val key = "$providerId/$modelId"
                        names[key] = ProviderConfigV2.DisabledModel(
                            providerId,
                            modelId,
                            model.optString("name")
                        )
                        // Более поздний документ конфигурации имеет приоритет.
                        if (model.optBoolean("disabled")) disabled += key else disabled -= key
                    }
                }
            }
        }
        return disabled.mapNotNull { names[it] }
    }

    /** Путь глобального opencode.json из `/api/config`, если сервер его отдаёт. */
    private fun configDocumentPath(): String? {
        val text = runCatching { get("/api/config") }.getOrNull() ?: return null
        val array = runCatching {
            if (text.trimStart().startsWith("[")) {
                JSONArray(text)
            } else {
                JSONObject(text).optJSONArray("data")
            }
        }.getOrNull() ?: return null
        for (index in array.length() - 1 downTo 0) {
            val entry = array.optJSONObject(index) ?: continue
            if (entry.optString("type") != "document") continue
            val path = entry.optString("path")
            if (path.endsWith("opencode.json") || path.endsWith("opencode.jsonc")) return path
        }
        return null
    }

    /**
     * Записывает opencode.json через `POST /api/experimental/fs/write`.
     * Если endpoint недоступен — тем же API запускает `base64 -d` на сервере.
     */
    fun writeConfig(path: String, content: String) {
        writeFile(path, content.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8", chmod600 = true)
    }

    /**
     * Загружает файл в `~/opencode-uploads` через HTTP API сервера.
     * Возвращает полный путь. Работает без отдельного SSH-соединения.
     */
    fun uploadFile(remoteDir: String, name: String, bytes: ByteArray): String {
        val path = uploadPath(remoteDir, name)
        writeFile(path, bytes, "application/octet-stream", chmod600 = false)
        return path
    }

    /** Загружает файл потоком; [size] = -1, если размер неизвестен. */
    fun uploadFile(remoteDir: String, name: String, size: Long, open: () -> InputStream): String {
        val path = uploadPath(remoteDir, name)
        try {
            postRaw(
                "/api/experimental/fs/write?path=${encode(path)}",
                streamBody(size, open)
            )
            return path
        } catch (e: Exception) {
            // Небольшие файлы можно дослать запасным shell-путём.
            if (size in 0..8L * 1024 * 1024) {
                writeFile(path, open().use { it.readBytes() }, "application/octet-stream", false)
                return path
            }
            throw e
        }
    }

    private fun uploadPath(remoteDir: String, name: String): String {
        val home = shell("printf '%s' \"\$HOME\"").trim()
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .ifBlank { "/root" }
        val dir = remoteDir.trim().ifBlank { "$home/opencode-uploads" }
        val safeName = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .trim('.', '_')
            .ifBlank { "file" }
            .takeLast(120)
        return "$dir/$safeName"
    }

    private fun streamBody(size: Long, open: () -> InputStream): RequestBody =
        object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input -> sink.writeAll(input.source()) }
            }
        }

    private fun writeFile(path: String, bytes: ByteArray, contentType: String, chmod600: Boolean) {
        try {
            postRaw(
                "/api/experimental/fs/write?path=${encode(path)}",
                bytes,
                contentType
            )
            return
        } catch (_: Exception) {
            // Ниже — резервный путь через shell для старых сборок сервера.
        }

        val encoded = Base64.getEncoder().encodeToString(bytes)
        val dir = path.substringBeforeLast('/', "")
        val command = buildString {
            if (dir.isNotBlank()) append("mkdir -p ").append(shellQuote(dir)).append("; ")
            append("printf '%s' ").append(shellQuote(encoded))
            append(" | base64 -d > ").append(shellQuote(path))
            if (chmod600) append("; chmod 600 ").append(shellQuote(path))
        }
        val output = shell(command)
        if (output.contains("Permission denied") || output.contains("Read-only file system")) {
            throw IllegalStateException(output.trim())
        }
    }

    fun storageInfo(): StorageInfo =
        ServerStorageCommands.parse(shell(ServerStorageCommands.INFO))

    fun clearUploads() {
        shell(ServerStorageCommands.CLEAR)
    }

    fun uploads(): List<String> =
        shell(ServerStorageCommands.LIST)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

    fun deleteUpload(name: String) {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        if (safe.isBlank()) return
        shell("d=\"\$HOME/opencode-uploads\"; rm -f -- \"\$d\"/" + shellQuote(safe))
    }

    fun usageLimits(): UsageLimitsInfo? {
        val root = JSONObject(get("/api/experimental/usage/limits"))
        val data = root.optJSONObject("data") ?: return null
        return ChatJson.usageLimits(data)
    }

    fun diffs(sessionId: String): List<FileDiffInfo> {
        val root = JSONObject(get("/api/session/${encode(sessionId)}/diff"))
        return ChatJson.diffs(root.optJSONArray("data") ?: JSONArray())
    }

    /** Запускает неинтерактивную команду и возвращает её stdout/stderr. */
    fun shell(command: String, timeoutMs: Long = 20_000): String {
        var last: Exception? = null
        // Сервер хранит только последние 25 завершённых команд. При серии
        // переключений старая команда может исчезнуть до чтения — повторяем.
        repeat(2) { attempt ->
            try {
                return shellOnce(command, timeoutMs)
            } catch (e: Exception) {
                last = e
                val notFound = e is OpenCodeException && e.status == 404
                if (!notFound || attempt == 1) throw e
                Thread.sleep(250)
            }
        }
        throw last ?: IllegalStateException("Не удалось выполнить shell-команду")
    }

    private fun shellOnce(command: String, timeoutMs: Long): String {
        val created = JSONObject(
            post(
                "/api/shell",
                JSONObject().put("command", command).put("timeout", timeoutMs)
            )
        ).optJSONObject("data") ?: JSONObject()
        val id = created.optString("id")
        if (id.isBlank()) throw IllegalStateException("Сервер не вернул ID shell-команды")

        var waited = 0L
        while (waited <= timeoutMs + 10_000) {
            val info = JSONObject(get("/api/shell/${encode(id)}")).optJSONObject("data") ?: JSONObject()
            if (info.optString("status") != "running") break
            Thread.sleep(150)
            waited += 150
        }
        return JSONObject(get("/api/shell/${encode(id)}/output?limit=1000000"))
            .optJSONObject("data")
            ?.optString("output")
            .orEmpty()
    }

    /** Возвращает JSON экспорта сессии (SessionTransfer.Data). */
    fun exportSession(sessionId: String, sanitize: Boolean = false): String =
        get("/api/experimental/session/${encode(sessionId)}/export?sanitize=$sanitize")

    /** Импорт сессии. Возвращает ответ сервера (обычно info+messages). */
    fun importSession(payload: JSONObject): String =
        post("/api/experimental/session/import", payload)

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun get(path: String): String = execute("GET", path, null)

    private fun post(path: String, body: JSONObject?): String =
        execute("POST", path, body ?: JSONObject())

    private fun patch(path: String, body: JSONObject): String = execute("PATCH", path, body)

    private fun delete(path: String): String = execute("DELETE", path, null)

    private fun execute(method: String, path: String, body: JSONObject?): String {
        val base = try {
            urlProvider().trimEnd('/')
        } catch (e: Exception) {
            throw OpenCodeException(0, e.message ?: "Не удалось открыть SSH-туннель")
        }
        val url = try {
            (base + path).toHttpUrl()
        } catch (e: Exception) {
            throw OpenCodeException(0, "Некорректный адрес OpenCode: $base")
        }

        val builder = Request.Builder().url(url)
        if (password.isNotEmpty()) {
            builder.header("Authorization", Credentials.basic("opencode", password))
        }
        val media = "application/json; charset=utf-8".toMediaType()
        val requestBody = body?.toString()?.toRequestBody(media)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: "".toRequestBody(media))
            "PATCH" -> builder.patch(requestBody ?: "".toRequestBody(media))
            "DELETE" -> builder.delete()
        }
        builder.header("Accept", "application/json")

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw OpenCodeException(0, networkMessage(e))
        }

        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw OpenCodeException(resp.code, errorMessage(resp.code, text))
            }
            return text
        }
    }

    private fun networkMessage(e: IOException): String {
        val text = e.message.orEmpty()
        return when {
            text.contains("Failed to connect", true) ||
                text.contains("Connection refused", true) ->
                "OpenCode недоступен: соединение отклонено. Проверьте, запущен ли сервер."

            text.contains("timeout", true) ->
                "OpenCode не ответил вовремя. Сервер занят или недоступен."

            text.contains("Unable to resolve host", true) ->
                "Не удалось разрешить адрес сервера."

            else -> "Ошибка сети: ${text.ifBlank { e.javaClass.simpleName }}"
        }
    }

    private fun errorMessage(status: Int, body: String): String {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<")) {
            val heading = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim()
            val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()
            val detail = listOfNotNull(heading, title)
                .firstOrNull { it.isNotBlank() && !it.equals("Bad Request", ignoreCase = true) }
                ?: "Сервер вернул HTML вместо JSON."
            return "Ошибка OpenCode ($status). $detail Проверьте адрес сервера и порт."
        }

        val detail = runCatching {
            val o = JSONObject(body)
            o.optString("message").ifBlank {
                o.optJSONObject("error")?.optString("message").orEmpty()
            }
        }.getOrNull().orEmpty().ifBlank { body.take(400).trim() }

        return when (status) {
            401 -> "Сервер требует пароль OpenCode (401). Проверьте пароль в настройках сервера."
            403 -> "Доступ запрещён (403). $detail"
            404 -> "Не найдено (404). $detail"
            409 -> "Сессия занята или уже существует (409). $detail"
            else -> "Ошибка OpenCode ($status). $detail"
        }
    }

    private fun postRaw(path: String, bytes: ByteArray, contentType: String): String =
        postRaw(path, bytes.toRequestBody(contentType.toMediaType()))

    private fun postRaw(path: String, body: RequestBody): String {
        val base = try {
            urlProvider().trimEnd('/')
        } catch (e: Exception) {
            throw OpenCodeException(0, e.message ?: "Не удалось открыть SSH-туннель")
        }
        val url = try {
            (base + path).toHttpUrl()
        } catch (e: Exception) {
            throw OpenCodeException(0, "Некорректный адрес OpenCode: $base")
        }
        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
        if (password.isNotEmpty()) {
            builder.header("Authorization", Credentials.basic("opencode", password))
        }
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw OpenCodeException(0, networkMessage(e))
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw OpenCodeException(resp.code, errorMessage(resp.code, text))
            }
            return text
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val CONFIG_PATH_SCRIPT =
            "export HOME=\$(getent passwd \$(id -u) | cut -d: -f6); " +
                "[ -n \"\$HOME\" ] || export HOME=/root; " +
                "if [ -f \"\$HOME/.config/opencode/opencode.jsonc\" ]; then " +
                "echo \"\$HOME/.config/opencode/opencode.jsonc\"; " +
                "elif [ -f \"\$HOME/.config/opencode/opencode.json\" ]; then " +
                "echo \"\$HOME/.config/opencode/opencode.json\"; " +
                "else echo \"\$HOME/.config/opencode/opencode.json\"; fi"
    }
}

/** Держит OkHttp и SSH-туннели; один экземпляр на приложение. */
class ConnectionHub {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        // Большие файлы на мобильной сети могут загружаться дольше.
        .writeTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private val tunnels = ConcurrentHashMap<String, Ssh.Tunnel>()

    fun api(server: ServerProfile): OpenCodeApi = OpenCodeApi(
        urlProvider = { baseUrl(server) },
        password = server.serverPassword,
        client = clientFor(server)
    )

    /** Отдельный клиент для прямого HTTPS с необязательным пином сертификата. */
    private fun clientFor(server: ServerProfile): OkHttpClient {
        if (server.mode != ru.opencode.mobile.data.ConnectionMode.DIRECT) return http
        val pin = server.tlsPin.trim()
        if (pin.isBlank()) return http
        val normalized = UrlTools.normalize(server.directUrl) ?: return http
        if (!normalized.startsWith("https://")) return http
        val host = runCatching { normalized.toHttpUrl().host }.getOrNull() ?: return http
        val pinValue = if (pin.startsWith("sha256/")) pin else "sha256/$pin"
        return try {
            http.newBuilder()
                .certificatePinner(CertificatePinner.Builder().add(host, pinValue).build())
                .build()
        } catch (e: Exception) {
            throw OpenCodeException(0, "Некорректный SHA-256 пин сертификата: ${e.message}")
        }
    }

    fun baseUrl(server: ServerProfile): String {
        return when (server.mode) {
            ru.opencode.mobile.data.ConnectionMode.DIRECT -> {
                val normalized = UrlTools.normalize(server.directUrl)
                    ?: throw OpenCodeException(0, "Не задан URL сервера OpenCode")
                if (normalized.startsWith("http://") &&
                    !UrlTools.isLocalUrl(normalized) &&
                    !server.allowInsecureHttp
                ) {
                    throw OpenCodeException(
                        0,
                        "HTTP без шифрования запрещён. Используйте HTTPS, SSH-туннель " +
                            "или включите разрешение HTTP в настройках сервера."
                    )
                }
                normalized
            }

            ru.opencode.mobile.data.ConnectionMode.TUNNEL -> {
                if (server.sshHost.isBlank()) {
                    throw OpenCodeException(0, "Не задан SSH-сервер")
                }
                if (server.sshPassword.isBlank() && server.sshPrivateKey.isBlank()) {
                    throw OpenCodeException(0, "Не задан SSH-пароль или ключ")
                }
                val target = SshTarget(
                    host = server.sshHost,
                    port = server.sshPort,
                    user = server.sshUser.ifBlank { "root" },
                    password = server.sshPassword,
                    privateKey = server.sshPrivateKey,
                    keyPassphrase = server.sshKeyPassphrase
                )
                val tunnel = tunnels.getOrPut(server.id) {
                    Ssh.Tunnel(target, server.sshFingerprint, server.remotePort)
                }
                val port = tunnel.ensurePort()
                "http://127.0.0.1:$port"
            }
        }
    }

    fun reset(serverId: String) {
        tunnels.remove(serverId)?.close()
    }

    fun closeAll() {
        tunnels.values.forEach { it.close() }
        tunnels.clear()
    }

    /** Тестовое подключение без исключений: возвращает версию или текст ошибки. */
    fun ping(server: ServerProfile): Pair<String?, String?> {
        return try {
            val info = api(server).info()
            info.optString("version") to null
        } catch (e: Exception) {
            null to (e.message ?: "Не удалось подключиться")
        }
    }

    /**
     * Проверка OpenAI-совместимого провайдера напрямую: `GET {baseURL}/models`
     * с Bearer-ключом. Возвращает (успех, количество моделей, текст ошибки).
     */
    fun checkOpenAiCompatible(baseUrl: String, apiKey: String): Triple<Boolean, Int, String> {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isBlank()) return Triple(false, 0, "не задан baseURL")
        val url = try {
            (normalized + "/models").toHttpUrl()
        } catch (e: Exception) {
            return Triple(false, 0, "некорректный baseURL")
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                            .ifBlank { JSONObject(body).optString("message") }
                    }.getOrDefault("").ifBlank { body.take(160) }
                    Triple(false, 0, "HTTP ${response.code} $detail".trim())
                } else {
                    val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
                    Triple(true, data?.length() ?: 0, "")
                }
            }
        } catch (e: Exception) {
            Triple(false, 0, e.message ?: "сеть недоступна")
        }
    }
}
