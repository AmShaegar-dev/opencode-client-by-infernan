package ru.opencode.mobile.data

import org.json.JSONObject

/** Слияние пользовательского провайдера с opencode.json на сервере. */
object ProviderConfig {

    const val DEFAULT_PACKAGE = "@opencode/ai/providers/openai-compatible"

    fun merge(existing: String?, provider: CustomProvider): String {
        val root = if (existing.isNullOrBlank()) {
            JSONObject().put("\$schema", "https://opencode.ai/config.json")
        } else {
            try {
                JSONObject(ProviderConfigV2.parseLenient(existing))
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Файл конфигурации OpenCode содержит комментарии или ошибку JSON. " +
                        "Добавьте провайдера вручную или приведите opencode.json к чистому JSON."
                )
            }
        }

        val providers = root.optJSONObject("providers") ?: JSONObject().also {
            root.put("providers", it)
        }

        val entry = JSONObject().apply {
            put("name", provider.name.ifBlank { provider.id })
            put("package", ProviderConfigV2.normalizePackage(provider.packageName))
            val settings = JSONObject()
            if (provider.baseUrl.isNotBlank()) settings.put("baseURL", provider.baseUrl.trim())
            if (provider.apiKey.isNotBlank()) settings.put("apiKey", provider.apiKey.trim())
            if (settings.length() > 0) put("settings", settings)
            val models = JSONObject()
            provider.models.forEach { model ->
                if (model.isNotBlank()) models.put(model.trim(), JSONObject())
            }
            if (models.length() > 0) put("models", models)
        }
        providers.put(provider.id, entry)
        return root.toString(2) + "\n"
    }

    fun remove(existing: String?, providerId: String): String {
        if (existing.isNullOrBlank()) return ""
        val root = try {
            JSONObject(ProviderConfigV2.parseLenient(existing))
        } catch (e: Exception) {
            throw IllegalStateException("Не удалось разобрать opencode.json: ${e.message}")
        }
        val providers = root.optJSONObject("providers") ?: return root.toString(2) + "\n"
        providers.remove(providerId)
        if (providers.length() == 0) root.remove("providers")
        return root.toString(2) + "\n"
    }
}
