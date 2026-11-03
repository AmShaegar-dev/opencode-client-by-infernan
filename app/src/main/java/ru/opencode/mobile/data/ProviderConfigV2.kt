package ru.opencode.mobile.data

import org.json.JSONObject

/**
 * Исправление «legacy»-формата провайдеров (V1 / `npm` / `options`) на нативный V2.
 *
 * Провайдеры, добавленные по инструкции из панели хостинга, обычно выглядят так:
 *
 * [jsonc]
 * {
 *   "provider": {
 *     "bobiviphouse": {
 *       "npm": "@ai-sdk/openai-compatible",
 *       "options": { "baseURL": "https://…/v1", "apiKey": "sk-…" },
 *       "models": { "gpt-6.1-sol": { "name": "gpt-6.1-sol" } }
 *     }
 *   }
 * }
 *
 * V2 такие поля читает, но `package` ждёт runtime-пакет OpenCode, а ключи
 * проверяются по нативному формату, поэтому при сохранении из приложения
 * переводим записи в V2-вид и не теряем остальные настройки.
 */
object ProviderConfigV2 {

    /** Модель, выключенная через `providers.<provider>.models.<model>.disabled`. */
    data class DisabledModel(
        val providerID: String,
        val modelID: String,
        val name: String = ""
    )

    /** AI SDK-пакеты получают префикс `aisdk:`, известные соответствия — нативный пакет. */
    fun normalizePackage(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ProviderConfig.DEFAULT_PACKAGE
        if (value.startsWith("aisdk:") || value.startsWith("file:")) return value

        // Сравниваем полное имя пакета, а не часть до первого «/»:
        // у scoped-пакетов (`@ai-sdk/...`) слэш стоит внутри имени.
        val aliases = listOf(
            "@ai-sdk/openai-compatible" to ProviderConfig.DEFAULT_PACKAGE,
            "@ai-sdk/openai" to "@opencode/ai/providers/openai",
            "@ai-sdk/anthropic" to "@opencode/ai/providers/anthropic",
            "@ai-sdk/google" to "@opencode/ai/providers/google",
            "@ai-sdk/google-vertex" to "@opencode/ai/providers/google-vertex",
            "@ai-sdk/azure" to "@opencode/ai/providers/azure",
            "@ai-sdk/amazon-bedrock" to "@opencode/ai/providers/amazon-bedrock",
            "@ai-sdk/xai" to "@opencode/ai/providers/xai",
            "@openrouter/ai-sdk-provider" to "@opencode/ai/providers/openrouter"
        )
        for ((source, target) in aliases) {
            if (value == source) return target
            if (value.startsWith("$source/")) return target + value.removePrefix(source)
        }
        // Родные пакеты OpenCode не трогаем, сторонние оборачиваем в `aisdk:`.
        return if (value.startsWith("@opencode/")) value else "aisdk:$value"
    }

    /** Модель V1 → V2: `id` → `modelID`, `release_date` → `releaseDate`. */
    fun normalizeModel(value: JSONObject): JSONObject {
        val model = JSONObject(value.toString())
        model.optString("id").takeIf { it.isNotBlank() }?.let { id ->
            if (!model.has("modelID")) model.put("modelID", id)
            model.remove("id")
        }
        model.optString("release_date").takeIf { it.isNotBlank() }?.let { date ->
            if (!model.has("releaseDate")) model.put("releaseDate", date)
            model.remove("release_date")
        }
        if (model.optBoolean("disabled")) {
            model.put("disabled", true)
        }
        return model
    }

    /**
     * Приводит запись провайдера к V2: `npm` → `package`, `options` → `settings`,
     * `api` → `settings.baseURL`.
     */
    fun normalizeProvider(value: JSONObject): JSONObject {
        val provider = JSONObject(value.toString())

        provider.optString("npm").takeIf { it.isNotBlank() }?.let { npm ->
            if (!provider.has("package")) provider.put("package", normalizePackage(npm))
            provider.remove("npm")
        }

        val settings = provider.optJSONObject("settings")?.let { JSONObject(it.toString()) } ?: JSONObject()

        provider.optJSONObject("options")?.let { options ->
            for (key in options.keys()) {
                if (!settings.has(key)) settings.put(key, options.get(key))
            }
            provider.remove("options")
        }

        provider.optString("api").takeIf { it.isNotBlank() }?.let { api ->
            if (!settings.has("baseURL")) settings.put("baseURL", api)
            provider.remove("api")
        }

        if (!provider.has("package")) provider.put("package", ProviderConfig.DEFAULT_PACKAGE)
        if (settings.length() > 0) provider.put("settings", settings)

        val models = provider.optJSONObject("models")
        if (models != null) {
            val normalized = JSONObject()
            for (key in models.keys()) {
                val model = models.optJSONObject(key)
                normalized.put(key, if (model == null) JSONObject() else normalizeModel(model))
            }
            provider.put("models", normalized)
        }

        provider.remove("id")
        provider.remove("whitelist")
        provider.remove("blacklist")
        return provider
    }

    /** Исправляет весь файл конфигурации на месте. Возвращает пары «что изменено». */
    fun normalizeConfig(raw: String): Pair<String, List<String>> {
        val root = try {
            JSONObject(parseLenient(raw))
        } catch (e: Exception) {
            throw IllegalStateException(
                "Файл opencode.json не является корректным JSON (комментарии или ошибка). " +
                    "Приведите его к JSON и повторите."
            )
        }
        val changes = mutableListOf<String>()

        // V1 `provider` → V2 `providers`
        val legacy = root.optJSONObject("provider")
        if (legacy != null) {
            val providers = root.optJSONObject("providers") ?: JSONObject().also { root.put("providers", it) }
            for (key in legacy.keys()) {
                if (!providers.has(key)) providers.put(key, legacy.get(key))
            }
            root.remove("provider")
            changes += "provider → providers"
        }

        root.optJSONObject("providers")?.let { providers ->
            for (key in providers.keys()) {
                val value = providers.optJSONObject(key) ?: continue
                val normalized = normalizeProvider(value)
                if (normalized.toString() != value.toString()) {
                    providers.put(key, normalized)
                    changes += key
                }
            }
        }
        return root.toString(2) + "\n" to changes
    }

    /**
     * Разрешает `{env:NAME}` / `${NAME}` в значении `settings.apiKey`, если удалось
     * прочитать переменную на сервере. Нужно, чтобы приложение могло проверить ключ.
     */
    fun resolveEnv(value: String, env: Map<String, String>): String {
        val match = Regex("""^\{env:([A-Za-z_][A-Za-z0-9_]*)\}$""").find(value.trim())
            ?: Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$""").find(value.trim())
        if (match == null) return value
        return env[match.groupValues[1]] ?: value
    }

    /**
     * JSONC → JSON: убирает комментарии вида двойной слэш и блочные, но не трогает текст внутри строк,
     * и удаляет запятые перед закрывающими скобками.
     */
    fun parseLenient(raw: String): String {
        val out = StringBuilder(raw.length)
        var inString = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                inString -> {
                    out.append(c)
                    when {
                        c == '\\' && i + 1 < raw.length -> {
                            out.append(raw[i + 1])
                            i++
                        }

                        c == '"' -> inString = false
                    }
                }

                c == '"' -> {
                    inString = true
                    out.append(c)
                }

                c == '/' && i + 1 < raw.length && raw[i + 1] == '/' -> {
                    while (i < raw.length && raw[i] != '\n') i++
                    continue
                }

                c == '/' && i + 1 < raw.length && raw[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) i++
                    i++
                }

                else -> out.append(c)
            }
            i++
        }
        return out.toString().replace(Regex(""",(\s*[}\]])"""), "$1")
    }

    /**
     * Удаляет провайдера из нативного `providers`, а также из legacy `provider`.
     * Возвращает пары «что изменено».
     */
    fun removeProvider(raw: String, providerId: String): Pair<String, List<String>> {
        val root = JSONObject(parseLenient(raw))
        val changes = mutableListOf<String>()
        root.optJSONObject("providers")?.let { providers ->
            if (providers.has(providerId)) {
                providers.remove(providerId)
                changes += providerId
            }
            if (providers.length() == 0) root.remove("providers")
        }
        root.optJSONObject("provider")?.let { providers ->
            if (providers.has(providerId)) {
                providers.remove(providerId)
                changes += providerId
            }
            if (providers.length() == 0) root.remove("provider")
        }
        return root.toString(2) + "\n" to changes
    }

    /**
     * Выключенные модели из `opencode.json`. Нужны, потому что `/api/model`
     * возвращает только включённые модели, и после выключения модель исчезает
     * из списка — без этого её нельзя было бы включить обратно.
     */
    fun disabledModels(raw: String): List<DisabledModel> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { JSONObject(parseLenient(raw)) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<DisabledModel>()
        for (section in listOf("providers", "provider")) {
            val providers = root.optJSONObject(section) ?: continue
            for (providerId in providers.keys()) {
                val models = providers.optJSONObject(providerId)?.optJSONObject("models") ?: continue
                for (modelId in models.keys()) {
                    val model = models.optJSONObject(modelId) ?: continue
                    if (model.optBoolean("disabled")) {
                        result += DisabledModel(providerId, modelId, model.optString("name"))
                    }
                }
            }
        }
        return result
    }

    /**
     * Включает или выключает модель в каталоге: `providers.<provider>.models.<model>.disabled`.
     * При включении пишем `disabled: false`, а не удаляем поле: OpenCode
     * применяет конфиг поверх уже загруженного каталога, и пропуск поля
     * оставляет модель выключенной до полного перезапуска.
     * Возвращает новый текст конфигурации и признак того, что файл изменился.
     */
    fun setModelDisabled(
        raw: String,
        providerId: String,
        modelId: String,
        disabled: Boolean,
        name: String = ""
    ): Pair<String, Boolean> {
        val root = if (raw.isBlank()) JSONObject() else JSONObject(parseLenient(raw))
        if (!root.has("\$schema")) root.put("\$schema", "https://opencode.ai/config.json")
        val providers = root.optJSONObject("providers") ?: JSONObject().also { root.put("providers", it) }
        val provider = providers.optJSONObject(providerId) ?: JSONObject().also { providers.put(providerId, it) }
        val models = provider.optJSONObject("models") ?: JSONObject().also { provider.put("models", it) }
        val existing = models.optJSONObject(modelId)
        val model = existing ?: JSONObject()

        val before = model.optBoolean("disabled")
        val changed = existing == null || before != disabled
        if (changed) {
            // false тоже записываем явно — это команда «включить обратно».
            model.put("disabled", disabled)
            if (name.isNotBlank() && !model.has("name")) model.put("name", name)
            models.put(modelId, model)
        }
        return root.toString(2) + "\n" to changed
    }

    /** Значение `settings.apiKey` одного провайдера или пустая строка. */
    fun apiKeyOf(raw: String, providerId: String): String {
        val root = JSONObject(parseLenient(raw))
        val provider = root.optJSONObject("providers")?.optJSONObject(providerId)
            ?: root.optJSONObject("provider")?.optJSONObject(providerId)
            ?: return ""
        return provider.optJSONObject("settings")?.optString("apiKey").orEmpty()
            .ifBlank { provider.optJSONObject("options")?.optString("apiKey").orEmpty() }
    }

    /** Идентификаторы провайдеров из обоих форматов. */
    fun providerIds(raw: String): List<String> {
        val root = JSONObject(parseLenient(raw))
        return buildList {
            root.optJSONObject("providers")?.let { for (key in it.keys()) add(key) }
            root.optJSONObject("provider")?.let { for (key in it.keys()) if (key !in this) add(key) }
        }
    }
}
