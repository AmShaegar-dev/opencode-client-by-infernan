package ru.opencode.mobile.data

import org.json.JSONArray
import org.json.JSONObject

enum class ConnectionMode {
    /** OpenCode слушает 127.0.0.1 на сервере, приложение делает SSH-проброс. */
    TUNNEL,

    /** Прямое подключение по URL (сервер слушает внешний адрес). */
    DIRECT
}

/**
 * Пользовательский провайдер (OpenAI-совместимый или другой пакет OpenCode).
 * Хранится локально, чтобы его можно было заново применить на сервере и
 * включить в резервную копию.
 */
data class CustomProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val packageName: String = "@opencode/ai/providers/openai-compatible",
    val models: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("packageName", packageName)
        put("models", JSONArray(models))
    }

    companion object {
        fun fromJson(o: JSONObject): CustomProvider? {
            val id = o.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
            val baseUrl = o.optString("baseUrl").trim()
            val models = o.optJSONArray("models")?.let { array ->
                buildList { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
            }.orEmpty()
            return CustomProvider(
                id = id,
                name = o.optString("name").ifBlank { id },
                baseUrl = baseUrl,
                apiKey = o.optString("apiKey"),
                packageName = o.optString("packageName")
                    .ifBlank { "@opencode/ai/providers/openai-compatible" },
                models = models
            )
        }
    }
}

/** Сервер с установленным OpenCode, сохранённый на устройстве. */
data class ServerProfile(
    val id: String,
    val name: String,
    val color: Int,
    val mode: ConnectionMode = ConnectionMode.TUNNEL,
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    /** Приватный ключ OpenSSH/PEM; используется вместо пароля, если задан. */
    val sshPrivateKey: String = "",
    val sshKeyPassphrase: String = "",
    val sshFingerprint: String = "",
    val remotePort: Int = 4096,
    val directUrl: String = "",
    /** Для старых профилей при чтении считается true, новые — false. */
    val allowInsecureHttp: Boolean = false,
    /** Необязательный SHA-256 пин TLS-сертификата для прямого HTTPS. */
    val tlsPin: String = "",
    val serverPassword: String = "",
    val defaultModel: String = "",
    /** Выбранный вариант модели (уровень размышлений): provider/model -> variant. */
    val variantByModel: Map<String, String> = emptyMap(),
    /** Избранные модели: provider/model. */
    val favoriteModels: Set<String> = emptySet(),
    val customProviders: List<CustomProvider> = emptyList(),
    val createdAt: Long = 0L
) {
    val displayHost: String
        get() = when (mode) {
            ConnectionMode.TUNNEL -> if (sshHost.isBlank()) "SSH не настроен" else "$sshHost:$sshPort"
            ConnectionMode.DIRECT -> directUrl.ifBlank { "URL не задан" }
        }

    val modeLabel: String
        get() = when (mode) {
            ConnectionMode.TUNNEL -> "SSH-туннель"
            ConnectionMode.DIRECT -> "Прямое подключение"
        }

    val canInstall: Boolean
        get() = sshHost.isNotBlank() && (sshPassword.isNotBlank() || sshPrivateKey.isNotBlank())

    fun toJson(includeSecrets: Boolean = true): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("color", color)
        put("mode", mode.name)
        put("sshHost", sshHost)
        put("sshPort", sshPort)
        put("sshUser", sshUser)
        put("sshPassword", if (includeSecrets) sshPassword else "")
        put("sshPrivateKey", if (includeSecrets) sshPrivateKey else "")
        put("sshKeyPassphrase", if (includeSecrets) sshKeyPassphrase else "")
        put("sshFingerprint", sshFingerprint)
        put("remotePort", remotePort)
        put("directUrl", directUrl)
        put("allowInsecureHttp", allowInsecureHttp)
        put("tlsPin", tlsPin)
        put("serverPassword", if (includeSecrets) serverPassword else "")
        put("defaultModel", defaultModel)
        put("variantByModel", JSONObject().apply {
            variantByModel.forEach { (key, value) -> put(key, value) }
        })
        put("favoriteModels", JSONArray().apply { favoriteModels.forEach { put(it) } })
        put("createdAt", createdAt)
        put("customProviders", JSONArray().apply { customProviders.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ServerProfile? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val mode = runCatching {
                ConnectionMode.valueOf(o.optString("mode", ConnectionMode.TUNNEL.name))
            }.getOrDefault(ConnectionMode.TUNNEL)
            val providers = o.optJSONArray("customProviders")?.let { array ->
                buildList {
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { CustomProvider.fromJson(it)?.let(::add) }
                    }
                }
            }.orEmpty()
            return ServerProfile(
                id = id,
                name = o.optString("name").ifBlank { o.optString("sshHost") },
                color = o.optInt("color", ServerColors.palette.first()),
                mode = mode,
                sshHost = o.optString("sshHost"),
                sshPort = o.optInt("sshPort", 22).takeIf { it in 1..65535 } ?: 22,
                sshUser = o.optString("sshUser").ifBlank { "root" },
                sshPassword = o.optString("sshPassword"),
                sshPrivateKey = o.optString("sshPrivateKey"),
                sshKeyPassphrase = o.optString("sshKeyPassphrase"),
                sshFingerprint = o.optString("sshFingerprint"),
                remotePort = o.optInt("remotePort", 4096).takeIf { it in 1..65535 } ?: 4096,
                directUrl = o.optString("directUrl"),
                // Старые профили не должны перестать работать после обновления.
                allowInsecureHttp = o.optBoolean("allowInsecureHttp", true),
                tlsPin = o.optString("tlsPin"),
                serverPassword = o.optString("serverPassword"),
                defaultModel = o.optString("defaultModel"),
                variantByModel = o.optJSONObject("variantByModel")?.let { variants ->
                    buildMap {
                        for (key in variants.keys()) {
                            variants.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
                        }
                    }
                }.orEmpty(),
                favoriteModels = o.optJSONArray("favoriteModels")?.let { array ->
                    buildSet {
                        for (i in 0 until array.length()) {
                            array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }.orEmpty(),
                customProviders = providers,
                createdAt = o.optLong("createdAt", 0L)
            )
        }
    }
}

object ServerColors {
    val palette: List<Int> = listOf(
        0xFFF5A524.toInt(),
        0xFF5B8CFF.toInt(),
        0xFF2DD4A7.toInt(),
        0xFF8B5CF6.toInt(),
        0xFFFF6B6B.toInt(),
        0xFF38BDF8.toInt(),
        0xFFF472B6.toInt(),
        0xFFA3E635.toInt()
    )

    fun next(existing: List<ServerProfile>): Int = palette[existing.size % palette.size]
}

object UrlTools {
    /** Приводит адрес к виду http://host:port без хвостового слеша. */
    fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "http://$value"
        }
        return value.trimEnd('/')
    }

    fun hostOf(raw: String): String? {
        val normalized = normalize(raw) ?: return null
        return runCatching { java.net.URI(normalized).host?.removeSurrounding("[", "]") }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** localhost, петля, частные сети и эмулятор — HTTP здесь допустим. */
    fun isLocalUrl(raw: String): Boolean {
        val host = hostOf(raw)?.lowercase() ?: return false
        if (host == "localhost" || host == "::1" || host == "0.0.0.0") return true
        if (host.endsWith(".local") || host.endsWith(".lan")) return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when {
            parts[0] == 127 -> true
            parts[0] == 10 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 169 && parts[1] == 254 -> true
            else -> false
        }
    }

    fun isInsecureExternal(raw: String): Boolean {
        val normalized = normalize(raw) ?: return false
        return normalized.startsWith("http://", ignoreCase = true) && !isLocalUrl(normalized)
    }
}
