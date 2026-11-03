package ru.opencode.mobile.ui

import org.json.JSONObject
import ru.opencode.mobile.data.CustomProvider
import ru.opencode.mobile.data.ProviderConfig
import ru.opencode.mobile.data.ProviderConfigV2

/**
 * Разбор конфигурации провайдера, полученной вручную.
 * Понимает V2 (`providers`), V1 (`provider`/`npm`/`options`) и голый набор
 * провайдеров — как в инструкциях панелей хостинга.
 */
data class PendingProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val packageName: String,
    val models: List<String>,
    val legacy: Boolean
) {
    fun toCustom(): CustomProvider = CustomProvider(
        id = id,
        name = name.ifBlank { id },
        baseUrl = baseUrl,
        apiKey = apiKey,
        packageName = packageName,
        models = models
    )

    companion object {

        /** Возвращает список провайдеров из JSON или пустой список, если не нашлось. */
        fun fromJson(raw: String): List<PendingProvider> {
            val text = raw.trim()
            if (text.isEmpty()) return emptyList()
            val root = runCatching { JSONObject(ProviderConfigV2.parseLenient(text)) }.getOrNull()
                ?: return emptyList()

            val (normalized, legacy) = runCatching { ProviderConfigV2.normalizeConfig(root.toString()) }
                .getOrElse { root.toString() to emptyList() }
            val parsed = runCatching { JSONObject(normalized) }.getOrNull() ?: return emptyList()

            val providersObject = parsed.optJSONObject("providers")
                ?: parsed.optJSONObject("provider")
                ?: parsed

            val result = mutableListOf<PendingProvider>()
            for (id in providersObject.keys()) {
                val entry = providersObject.optJSONObject(id) ?: continue
                // Пропускаем служебные поля верхнего уровня, если это был голый объект.
                if (entry.length() == 0 && !entry.has("settings")) continue
                val settings = entry.optJSONObject("settings") ?: entry.optJSONObject("options")
                val baseUrl = settings?.optString("baseURL").orEmpty()
                    .ifBlank { entry.optString("api") }
                val packageName = entry.optString("package")
                    .ifBlank { entry.optString("npm") }
                    .ifBlank { ProviderConfig.DEFAULT_PACKAGE }
                val models = entry.optJSONObject("models")?.let { map ->
                    buildList { for (key in map.keys()) add(key) }
                }.orEmpty()
                result += PendingProvider(
                    id = id,
                    name = entry.optString("name").ifBlank { id },
                    baseUrl = baseUrl,
                    apiKey = settings?.optString("apiKey").orEmpty(),
                    packageName = packageName,
                    models = models,
                    legacy = legacy.isNotEmpty() || entry.has("npm") || entry.has("options")
                )
            }
            return result.filter { it.baseUrl.isNotBlank() || it.models.isNotEmpty() || it.id.isNotBlank() }
        }
    }
}
