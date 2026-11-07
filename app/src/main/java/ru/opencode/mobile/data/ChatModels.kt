package ru.opencode.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Ссылка на модель OpenCode: providerID + id (ключ модели). */
data class ModelRef(
    val id: String,
    val providerID: String,
    val variant: String? = null
) {
    val key: String get() = "$providerID/$id"

    fun label(): String = key

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("providerID", providerID)
        if (!variant.isNullOrBlank()) put("variant", variant)
    }

    companion object {
        fun fromJson(o: JSONObject?): ModelRef? {
            if (o == null) return null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val provider = o.optString("providerID").takeIf { it.isNotBlank() } ?: return null
            return ModelRef(id, provider, o.optString("variant").takeIf { it.isNotBlank() })
        }

        fun parse(value: String?): ModelRef? {
            val text = value?.trim().orEmpty()
            val slash = text.indexOf('/')
            if (slash <= 0 || slash == text.lastIndex) return null
            return ModelRef(text.substring(slash + 1), text.substring(0, slash))
        }
    }
}

data class PromptAttachment(
    val uri: String,
    val name: String,
    val sizeBytes: Long = 0L
)

data class ModelInfo(
    val ref: ModelRef,
    val modelID: String,
    val name: String,
    val enabled: Boolean,
    val free: Boolean,
    val contextLimit: Long,
    val variants: List<String>
) {
    val title: String get() = if (name.isBlank()) ref.id else name
    val subtitle: String get() = ref.providerID
}

data class UsageMeterInfo(
    val label: String,
    val percent: Double,
    val limited: Boolean,
    val resetsAt: Long
)

data class UsageBudgetInfo(
    val limit: Double,
    val spent: Double,
    val resetsAt: Long
)

data class UsageLimitsInfo(
    val meters: List<UsageMeterInfo> = emptyList(),
    val budgets: List<UsageBudgetInfo> = emptyList(),
    val usageUrl: String = ""
) {
    val isEmpty: Boolean get() = meters.isEmpty() && budgets.isEmpty()
}

data class FileDiffInfo(
    val file: String,
    val patch: String,
    val additions: Int,
    val deletions: Int,
    val status: String
)

data class CommandInfo(
    val name: String,
    val description: String = ""
)

data class AgentInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val mode: String = ""
)

data class SessionInfo(
    val id: String,
    val title: String,
    val created: Long,
    val updated: Long,
    val cost: Double,
    val model: ModelRef?,
    val agent: String?
) {
    val displayTitle: String get() = title.ifBlank { "Новый чат" }
    val updatedMillis: Long get() = if (updated <= 0) 0L else if (updated < 10_000_000_000L) updated * 1000 else updated
    val createdMillis: Long get() = if (created <= 0) 0L else if (created < 10_000_000_000L) created * 1000 else created
}

data class ToolCall(
    val name: String,
    val status: String,
    val input: String,
    val output: String
) {
    val isRunning: Boolean get() = status == "running" || status == "streaming"
}

/** Сообщение в нормализованном виде для интерфейса. */
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val reasoning: String = "",
    val tools: List<ToolCall> = emptyList(),
    val created: Long = 0L,
    val completed: Boolean = true,
    val modelLabel: String = "",
    val error: String = "",
    /** Имена вложений, отправленных вместе с сообщением. */
    val files: List<String> = emptyList()
) {
    /** Текст для отображения: служебные маркеры вложений убираем. */
    val displayText: String
        get() = Regex("📎\\s*\\S+").replace(text, "").trim()

    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"
    val isHidden: Boolean get() = role == "hidden"
}

data class ProviderInfo(
    val id: String,
    val name: String,
    val integrationID: String,
    val activation: String
)

/** Способ входа, который предлагает интеграция провайдера. */
data class IntegrationMethod(
    val id: String,
    val type: String,
    val label: String
) {
    val isKey: Boolean get() = type == "key"
    val isOAuth: Boolean get() = type == "oauth"
    val isExternal: Boolean get() = type == "external"
}

/** Сохранённый аккаунт провайдера (ключ, OAuth-токен или переменная сервера). */
data class IntegrationConnection(
    val id: String,
    val label: String,
    val method: String,
    val type: String,
    val active: Boolean = true
)

data class IntegrationInfo(
    val id: String,
    val name: String,
    val methods: List<IntegrationMethod>,
    val connections: List<IntegrationConnection> = emptyList()
) {
    val supportsKey: Boolean get() = methods.any { it.isKey }
    val supportsOAuth: Boolean get() = methods.any { it.isOAuth }
    val isConnected: Boolean get() = connections.isNotEmpty()
    val methodTypes: List<String> get() = methods.map { it.type }.distinct()

    fun method(type: String): IntegrationMethod? = methods.firstOrNull { it.type == type }
}

/** Начатая попытка OAuth-входа. */
data class OAuthAttemptInfo(
    val attemptId: String,
    val url: String,
    val instructions: String,
    /** `auto` — браузер сам вернёт код на сервер, `code` — код нужно вставить вручную. */
    val mode: String
)

data class ServerStatus(
    val online: Boolean = false,
    val checking: Boolean = false,
    val version: String = "",
    val error: String = "",
    val checkedAt: Long = 0L
)

/** Разбор ответов API OpenCode. */
object ChatJson {

    fun session(o: JSONObject): SessionInfo {
        val time = o.optJSONObject("time")
        return SessionInfo(
            id = o.optString("id"),
            title = TextCleaner.clean(o.optString("title")),
            created = epochSeconds(time?.opt("created")),
            updated = epochSeconds(time?.opt("updated")),
            cost = o.optDouble("cost", 0.0),
            model = ModelRef.fromJson(o.optJSONObject("model")),
            agent = o.optString("agent").takeIf { it.isNotBlank() }
        )
    }

    /** Время из API: double-секунды, иногда строка или миллисекунды. */
    private fun epochSeconds(value: Any?): Long {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull() ?: return 0L
            else -> return 0L
        }
        return if (number > 1_000_000_000_000.0) number.toLong() else (number * 1000).toLong()
    }

    fun sessions(array: JSONArray): List<SessionInfo> = buildList {
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { add(session(it)) }
        }
    }

    /** Сырой ответ `/api/session` (в том числе из шифрованного кэша). */
    fun sessions(raw: String): List<SessionInfo> =
        sessions(JSONObject(raw).optJSONArray("data") ?: JSONArray())

    fun model(o: JSONObject): ModelInfo {
        val costs = o.optJSONArray("cost")
        // Нулевые цены (в том числе массив из нулей) — бесплатная модель.
        var free = true
        if (costs != null) {
            for (i in 0 until costs.length()) {
                val c = costs.optJSONObject(i) ?: continue
                if (c.optDouble("input", 0.0) > 0.0 || c.optDouble("output", 0.0) > 0.0) {
                    free = false
                    break
                }
            }
        }
        val variants = o.optJSONArray("variants")?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.orEmpty()
        val limit = o.optJSONObject("limit")
        return ModelInfo(
            ref = ModelRef(
                id = o.optString("id"),
                providerID = o.optString("providerID")
            ),
            modelID = o.optString("modelID"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            free = free,
            contextLimit = limit?.optLong("context", 0L) ?: 0L,
            variants = variants
        )
    }

    fun models(array: JSONArray): List<ModelInfo> = buildList {
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { add(model(it)) }
        }
    }

    fun usageLimits(o: JSONObject): UsageLimitsInfo {
        val meters = buildList {
            val go = o.optJSONObject("go") ?: return@buildList
            listOf(
                "Скользящий" to "rolling",
                "Неделя" to "weekly",
                "Месяц" to "monthly"
            ).forEach { (label, key) ->
                val meter = go.optJSONObject(key) ?: return@forEach
                add(
                    UsageMeterInfo(
                        label = label,
                        percent = meter.optDouble("percent", 0.0).coerceIn(0.0, 100.0),
                        limited = meter.optBoolean("limited"),
                        resetsAt = meter.optLong("resetsAt")
                    )
                )
            }
        }
        val budgets = buildList {
            val array = o.optJSONArray("budgets") ?: return@buildList
            for (i in 0 until array.length()) {
                val budget = array.optJSONObject(i) ?: continue
                add(
                    UsageBudgetInfo(
                        limit = budget.optDouble("limit", 0.0),
                        spent = budget.optDouble("spent", 0.0),
                        resetsAt = budget.optLong("resetsAt")
                    )
                )
            }
        }
        return UsageLimitsInfo(
            meters = meters,
            budgets = budgets,
            usageUrl = o.optJSONObject("workspace")?.optString("usageURL").orEmpty()
        )
    }

    fun diffs(array: JSONArray): List<FileDiffInfo> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                FileDiffInfo(
                    file = o.optString("file"),
                    patch = o.optString("patch"),
                    additions = o.optInt("additions", 0),
                    deletions = o.optInt("deletions", 0),
                    status = o.optString("status")
                )
            )
        }
    }

    fun commands(array: JSONArray): List<CommandInfo> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isBlank()) continue
            add(CommandInfo(name, o.optString("description")))
        }
    }

    fun agents(array: JSONArray): List<AgentInfo> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            add(
                AgentInfo(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    description = o.optString("description"),
                    mode = o.optString("mode")
                )
            )
        }
    }

    fun providers(array: JSONArray): List<ProviderInfo> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            add(
                ProviderInfo(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    integrationID = o.optString("integrationID"),
                    activation = o.optString("activation")
                )
            )
        }
    }

    fun integrations(array: JSONArray): List<IntegrationInfo> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            add(integration(o))
        }
    }

    fun integration(o: JSONObject): IntegrationInfo {
        val id = o.optString("id")
        val methods = o.optJSONArray("methods")?.let { list ->
            buildList {
                for (j in 0 until list.length()) {
                    val item = list.optJSONObject(j) ?: continue
                    val type = item.optString("type").ifBlank { "key" }
                    add(
                        IntegrationMethod(
                            id = item.optString("id").ifBlank { type },
                            type = type,
                            label = item.optString("label").ifBlank {
                                when (type) {
                                    "oauth" -> "Войти через браузер"
                                    "key" -> "API-ключ"
                                    "external" -> "Внешний вход"
                                    "command" -> "Вход командой"
                                    else -> type
                                }
                            }
                        )
                    )
                }
            }
        }.orEmpty()
        val connections = o.optJSONArray("connections")?.let { list ->
            buildList {
                for (j in 0 until list.length()) {
                    val item = list.optJSONObject(j) ?: continue
                    val type = item.optString("type")
                    val key = item.optString("id").ifBlank { item.optString("name") }
                    if (key.isBlank() && type.isBlank()) continue
                    add(
                        IntegrationConnection(
                            id = key,
                            label = item.optString("label").ifBlank { item.optString("name").ifBlank { key } },
                            method = item.optString("method").ifBlank { type },
                            type = type
                        )
                    )
                }
            }
        }.orEmpty()
        return IntegrationInfo(
            id = id,
            name = o.optString("name").ifBlank { id },
            methods = methods,
            connections = connections
        )
    }

    fun oauthAttempt(o: JSONObject): OAuthAttemptInfo = OAuthAttemptInfo(
        attemptId = o.optString("attemptID"),
        url = o.optString("url"),
        instructions = o.optString("instructions"),
        mode = o.optString("mode").ifBlank { "auto" }
    )

    /**
     * Приводит сообщение API к плоской модели.
     * Типы OpenCode: user, assistant, system, synthetic, shell, idle, compaction…
     */
    fun message(o: JSONObject): ChatMessage? {
        val type = o.optString("type")
        val id = o.optString("id")
        val time = epochSeconds(o.optJSONObject("time")?.opt("created"))

        when (type) {
            "user" -> {
                val files = o.optJSONArray("files")?.let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            val name = item.optString("name")
                                .ifBlank { item.optString("uri").substringAfterLast('/') }
                            if (name.isNotBlank()) add(name)
                        }
                    }
                }.orEmpty()
                return ChatMessage(
                    id = id,
                    role = "user",
                    text = TextCleaner.clean(o.optString("text")),
                    created = time,
                    files = files
                )
            }

            "assistant" -> {
                val text = StringBuilder()
                val reasoning = StringBuilder()
                val tools = mutableListOf<ToolCall>()
                val content = o.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        when (part.optString("type")) {
                            "text" -> {
                                val value = part.optString("text")
                                if (text.isNotEmpty() && value.isNotBlank()) text.append("\n\n")
                                text.append(value)
                            }

                            "reasoning" -> {
                                val value = part.optString("text")
                                if (reasoning.isNotEmpty() && value.isNotBlank()) reasoning.append("\n\n")
                                reasoning.append(value)
                            }

                            "tool" -> tools += tool(part)
                        }
                    }
                }
                val completed = o.optJSONObject("time")?.has("completed") == true
                val modelRef = ModelRef.fromJson(o.optJSONObject("model"))
                val structured = o.optJSONObject("error")
                val error = (
                    structured?.optString("message").orEmpty().ifBlank {
                        structured?.optString("type").orEmpty()
                    }
                ).takeIf { it.isNotBlank() }.orEmpty()
                return ChatMessage(
                    id = id,
                    role = "assistant",
                    text = TextCleaner.clean(text.toString().trim()),
                    reasoning = TextCleaner.clean(reasoning.toString().trim()),
                    tools = tools,
                    created = time,
                    completed = completed,
                    modelLabel = modelRef?.key.orEmpty(),
                    error = TextCleaner.clean(error)
                )
            }

            "system", "synthetic", "shell" -> {
                val value = o.optString("text").ifBlank { o.optString("command") }
                if (value.isBlank()) return null
                return ChatMessage(
                    id = id,
                    role = "hidden",
                    text = TextCleaner.clean(value),
                    created = time
                )
            }

            else -> return null
        }
    }

    private fun tool(o: JSONObject): ToolCall {
        val state = o.optJSONObject("state")
        val status = state?.optString("status").orEmpty()
        val inputObject = state?.optJSONObject("input")
        val inputText = state?.optString("input").orEmpty()
        val input = when {
            inputObject != null -> pretty(inputObject)
            inputText.isNotBlank() -> inputText
            else -> ""
        }
        val output = state?.optJSONArray("content")?.let { content ->
            buildString {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    val text = item.optString("text")
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(text)
                    }
                }
            }
        }.orEmpty()
        return ToolCall(
            name = o.optString("name").ifBlank { "tool" },
            status = status,
            input = TextCleaner.clean(input),
            output = TextCleaner.clean(output)
        )
    }

    fun messages(array: JSONArray): List<ChatMessage> = buildList {
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { message(it)?.let(::add) }
        }
    }

    /**
     * Сырой ответ `/api/session/{id}/message`. Сервер отдаёт новые сообщения
     * первыми (`order=desc`), для чата разворачиваем в хронологический порядок.
     */
    fun messages(raw: String): List<ChatMessage> =
        messages(JSONObject(raw).optJSONArray("data") ?: JSONArray()).reversed()

    private fun pretty(o: JSONObject): String = runCatching { o.toString(2) }.getOrDefault(o.toString())
}
