package ru.opencode.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Консольный прогон проверок бизнес-логики: без Android и без JUnit.
 *
 * Запуск на собранных классах:
 *   java -cp "app/build/tmp/kotlin-classes/debug;<org.json.jar>;<kotlin-stdlib.jar>" \
 *        ru.opencode.mobile.data.Runner
 *
 * Используется для быстрой проверки разбора ответов API, конфигурации
 * провайдеров и профилей серверов после правок.
 */
object Runner {

    private var failures = 0
    private var passed = 0

    private fun check(name: String, detail: () -> String = { "" }, condition: () -> Boolean) {
        if (condition()) {
            passed++
            println("PASS  $name")
        } else {
            failures++
            println("FAIL  $name ${detail()}")
        }
    }

    private fun <T> eq(name: String, expected: T, actual: T) =
        check(name, { "expected=<$expected> actual=<$actual>" }) { expected == actual }

    private fun json(text: String): JSONObject = JSONObject(text)

    @JvmStatic
    fun main(args: Array<String>) {
        chatMessages()
        models()
        sessions()
        providers()
        providerConfig()
        modelToggles()
        profiles()
        updateVersions()
        updateManifest()
        textCleaner()
        displayMerge()
        passwords()
        println()
        println(if (failures == 0) "ALL CHECKS PASSED ($passed)" else "FAILURES: $failures (passed $passed)")
        if (failures != 0) kotlin.system.exitProcess(1)
    }

    private fun chatMessages() {
        val user = ChatJson.message(
            json("""{"id":"m1","type":"user","time":{"created":1700000000.5},"text":"Привет"}""")
        )
        eq("user role", "user", user?.role)
        eq("user text", "Привет", user?.text)
        eq("double seconds to millis", 1_700_000_000_500L, user?.created)

        val assistant = ChatJson.message(
            json(
                """{"id":"m2","type":"assistant","time":{"created":1700000000.0,"completed":1700000005.0},
                    "agent":"build","model":{"id":"free","providerID":"mock"},"content":[
                     {"type":"reasoning","text":"думаю"},
                     {"type":"text","text":"Готово"},
                     {"type":"tool","id":"t1","name":"bash","time":{"created":1},
                      "state":{"status":"completed","input":{"command":"ls"},
                               "content":[{"type":"text","text":"file.txt"}]}}]}"""
            )
        )
        eq("assistant text", "Готово", assistant?.text)
        eq("assistant reasoning", "думаю", assistant?.reasoning)
        eq("assistant tools", 1, assistant?.tools?.size)
        eq("tool status", "completed", assistant?.tools?.firstOrNull()?.status)
        eq("tool output", "file.txt", assistant?.tools?.firstOrNull()?.output)
        check("assistant completed") { assistant?.completed == true }
        eq("model label", "mock/free", assistant?.modelLabel)

        val running = ChatJson.message(
            json(
                """{"id":"m3","type":"assistant","time":{"created":1.5},"agent":"b",
                    "model":{"id":"x","providerID":"p"},"content":[]}"""
            )
        )
        check("running not completed") { running?.completed == false }

        val failed = ChatJson.message(
            json(
                """{"id":"m4","type":"assistant","time":{"created":1.0,"completed":2.0},"agent":"b",
                    "model":{"id":"x","providerID":"p"},"content":[],
                    "error":{"type":"ProviderAuthError","message":"Invalid API key"}}"""
            )
        )
        eq("structured error message", "Invalid API key", failed?.error)

        check("system message hidden") {
            ChatJson.message(json("""{"id":"m5","type":"system","time":{"created":1.0},"text":"sys"}"""))?.isHidden == true
        }
        check("unknown message ignored") {
            ChatJson.message(json("""{"id":"m6","type":"idle","time":{"created":1.0}}""")) == null
        }
    }

    private fun models() {
        val models = ChatJson.models(
            JSONArray(
                """[
                 {"id":"free","modelID":"free","providerID":"p","name":"Free","enabled":true,
                  "cost":[{"input":0,"output":0,"cache":{"read":0,"write":0}}],
                  "limit":{"context":128000,"output":8192},"variants":[],"status":"active"},
                 {"id":"paid","modelID":"paid","providerID":"p","name":"Paid","enabled":true,
                  "cost":[{"input":3,"output":15,"cache":{"read":0.3,"write":3.75}}],
                  "limit":{"context":200000,"output":8192},"variants":[{"id":"high"}],"status":"active"}
                ]"""
            )
        )
        eq("models parsed", 2, models.size)
        check("free model", { "free=${models[0].free} title=${models[0].title}" }) { models[0].free }
        check("paid model") { !models[1].free }
        eq("context limit", 128000L, models[0].contextLimit)
        eq("variants", listOf("high"), models[1].variants)
        eq("model key", "p/free", models[0].ref.key)

        eq(
            "commands parsed",
            listOf("compact"),
            ChatJson.commands(JSONArray("""[{"name":"compact","description":"Сжать"}]""")).map { it.name }
        )
        eq(
            "agents parsed",
            listOf("build"),
            ChatJson.agents(JSONArray("""[{"id":"build","name":"Build","mode":"primary"}]""")).map { it.id }
        )
        val usage = ChatJson.usageLimits(
            json(
                """{"workspace":{"id":"w","usageURL":"https://x"},
                    "go":{"rolling":{"percent":42,"limited":false,"resetsAt":1},
                          "weekly":{"percent":70,"limited":false,"resetsAt":2},
                          "monthly":{"percent":100,"limited":true,"resetsAt":3}},
                    "budgets":[{"limit":20,"spent":7.5,"resetsAt":4}]}"""
            )
        )
        eq("usage meters", 3, usage.meters.size)
        eq("usage percent", 42.0, usage.meters[0].percent)
        check("usage limited flag") { usage.meters[2].limited }
        eq("usage budget", 20.0, usage.budgets[0].limit)
        eq(
            "diff parsed",
            "Main.kt",
            ChatJson.diffs(
                JSONArray("""[{"file":"Main.kt","patch":"@@","additions":1,"deletions":2,"status":"modified"}]""")
            ).first().file
        )
    }

    private fun sessions() {
        val sessions = ChatJson.sessions(
            JSONArray(
                """[{"id":"s1","title":"Чат","time":{"created":1700000000.123,"updated":1700000100.5},
                     "model":{"id":"m","providerID":"p"},"cost":0.5}]"""
            )
        )
        eq("session created", 1_700_000_000_123L, sessions[0].created)
        eq("session updated millis", 1_700_000_100_500L, sessions[0].updatedMillis)
        eq("session model", "p/m", sessions[0].model?.key)
    }

    private fun providers() {
        val integrations = ChatJson.integrations(
            JSONArray(
                """[{"id":"anthropic","name":"Anthropic",
                     "methods":[{"id":"key","type":"key","label":"API key"},
                                {"id":"claude","type":"oauth","label":"Claude Pro"}],
                     "connections":[{"id":"cred_1","label":"Ноутбук","method":"oauth","type":"oauth"}]}]"""
            )
        )
        check("integration supports key") { integrations[0].supportsKey }
        check("integration supports oauth") { integrations[0].supportsOAuth }
        check("integration connected") { integrations[0].isConnected }
        eq("integration method labels", listOf("API key", "Claude Pro"), integrations[0].methods.map { it.label })
        eq("integration method type", "oauth", integrations[0].method("oauth")?.type)
        eq("connection label", "Ноутбук", integrations[0].connections[0].label)
        eq("connection method", "oauth", integrations[0].connections[0].method)

        val attempt = ChatJson.oauthAttempt(
            json(
                """{"attemptID":"att_1","url":"https://example.com/oauth?state=x",
                    "instructions":"Вставьте код","mode":"code","time":{"created":1}}"""
            )
        )
        eq("oauth attempt id", "att_1", attempt.attemptId)
        eq("oauth attempt mode", "code", attempt.mode)
        eq("oauth attempt instructions", "Вставьте код", attempt.instructions)
        eq("oauth default mode", "auto", ChatJson.oauthAttempt(json("""{"attemptID":"a"}""")).mode)

        val providers = ChatJson.providers(
            JSONArray("""[{"id":"anthropic","name":"Anthropic","integrationID":"anthropic","activation":"auto"}]""")
        )
        eq("provider integration", "anthropic", providers[0].integrationID)
    }

    /** Включение и выключение модели через `providers.<id>.models.<model>.disabled`. */
    private fun modelToggles() {
        val base = """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "model": "mock/free",
              "providers": {
                "mock": {
                  "name": "Mock",
                  "package": "@opencode/ai/providers/openai-compatible",
                  "settings": { "apiKey": "sk-1" },
                  "models": {
                    "free": { "name": "Free" },
                    "legacy": { "name": "Legacy" }
                  }
                }
              }
            }
        """.trimIndent()

        val (disabled, changed) = ProviderConfigV2.setModelDisabled(base, "mock", "legacy", true)
        check("disable changes config") { changed }
        val root = JSONObject(disabled)
        eq("default model kept", "mock/free", root.optString("model"))
        val models = root.getJSONObject("providers").getJSONObject("mock").getJSONObject("models")
        check("disabled flag written") { models.getJSONObject("legacy").optBoolean("disabled") }
        check("existing model fields kept") { models.getJSONObject("legacy").optString("name") == "Legacy" }
        check("other models untouched") { !models.getJSONObject("free").has("disabled") }
        check("provider settings kept") {
            root.getJSONObject("providers").getJSONObject("mock")
                .getJSONObject("settings").optString("apiKey") == "sk-1"
        }

        val (enabled, enabledChanged) = ProviderConfigV2.setModelDisabled(disabled, "mock", "legacy", false)
        check("enable changes config") { enabledChanged }
        val enabledModels = JSONObject(enabled)
            .getJSONObject("providers").getJSONObject("mock").getJSONObject("models")
        check("disabled flag set to false") {
            enabledModels.getJSONObject("legacy").has("disabled") &&
                !enabledModels.getJSONObject("legacy").optBoolean("disabled")
        }
        check("model kept after enable") { enabledModels.has("legacy") }
        check("disabledModels ignores enabled") {
            ProviderConfigV2.disabledModels(enabled).none { it.modelID == "legacy" }
        }
        eq(
            "disabledModels finds model",
            listOf("legacy"),
            ProviderConfigV2.disabledModels(disabled).map { it.modelID }
        )

        val (_, noChange) = ProviderConfigV2.setModelDisabled(enabled, "mock", "free", false)
        check("enable enabled model is no-op") { !noChange }

        val (created, createdChanged) = ProviderConfigV2.setModelDisabled("", "newp", "newm", true)
        check("disable into empty config") { createdChanged }
        val createdRoot = JSONObject(created)
        check("new provider model created") {
            createdRoot.getJSONObject("providers").getJSONObject("newp")
                .getJSONObject("models").getJSONObject("newm").optBoolean("disabled")
        }
        check("schema added") { createdRoot.has("\$schema") }

        val (cleaned, _) = ProviderConfigV2.setModelDisabled(created, "newp", "newm", false)
        val cleanedRoot = JSONObject(cleaned)
        check("created provider kept for re-enable") { cleanedRoot.has("providers") }
        check("created model re-enabled") {
            !cleanedRoot.getJSONObject("providers").getJSONObject("newp")
                .getJSONObject("models").getJSONObject("newm").optBoolean("disabled")
        }

        eq(
            "legacy disabled models parsed",
            listOf("bobiviphouse/gpt-6.1-sol"),
            ProviderConfigV2.disabledModels(
                """{"provider":{"bobiviphouse":{"models":{"gpt-6.1-sol":{"disabled":true}}}}}"""
            ).map { "${it.providerID}/${it.modelID}" }
        )
    }

    private fun providerConfig() {
        providerConfigLegacy()
        providerConfigFromScreenshot()
        providerConfigLenient()
        providerConfigSecure()
    }

    private fun providerConfigLegacy() {
        val provider = CustomProvider(
            id = "acme",
            name = "Acme",
            baseUrl = "https://llm.acme.example/v1",
            apiKey = "sk-1",
            models = listOf("qwen3-coder")
        )
        val merged = JSONObject(ProviderConfig.merge(null, provider))
        val entry = merged.getJSONObject("providers").getJSONObject("acme")
        eq("provider name", "Acme", entry.getString("name"))
        eq("provider package", ProviderConfig.DEFAULT_PACKAGE, entry.getString("package"))
        eq("provider baseURL", "https://llm.acme.example/v1", entry.getJSONObject("settings").getString("baseURL"))
        check("provider model") { entry.getJSONObject("models").has("qwen3-coder") }
        check("schema present") { merged.has("\$schema") }

        val existing = JSONObject()
            .put("model", "anthropic/claude")
            .put("providers", JSONObject().put("other", JSONObject().put("name", "Other")))
            .toString()
        val second = JSONObject(
            ProviderConfig.merge(existing, CustomProvider("mine", "Mine", "http://localhost:11434/v1", ""))
        )
        eq("existing model kept", "anthropic/claude", second.getString("model"))
        check("both providers present") {
            second.getJSONObject("providers").has("other") && second.getJSONObject("providers").has("mine")
        }
        check("no empty apiKey") {
            !second.getJSONObject("providers").getJSONObject("mine").getJSONObject("settings").has("apiKey")
        }

        val removed = JSONObject(
            ProviderConfig.remove(JSONObject().put("providers", JSONObject().put("x", JSONObject())).toString(), "x")
        )
        check("empty providers section removed") { !removed.has("providers") }

        val jsonc = runCatching { ProviderConfig.merge("{ // comment\n \"model\": \"x\" }", provider) }
        check("jsonc merged", { jsonc.exceptionOrNull()?.message.orEmpty() }) {
            jsonc.isSuccess && JSONObject(jsonc.getOrThrow()).optString("model") == "x"
        }

        eq("custom provider roundtrip", provider, CustomProvider.fromJson(provider.toJson()))
    }

    private fun profiles() {
        val server = ServerProfile(
            id = "s1",
            name = "Мой сервер",
            color = ServerColors.palette[1],
            sshHost = "10.0.0.1",
            sshPort = 2222,
            sshPassword = "secret",
            sshFingerprint = "SHA256:abc",
            serverPassword = "api-pass",
            defaultModel = "mock/free",
            createdAt = 42L
        )
        val restored = ServerProfile.fromJson(server.toJson())
        eq("profile roundtrip", server, restored)
        eq("ssh display", "10.0.0.1:2222", restored?.displayHost)

        val withVariant = server.copy(
            variantByModel = mapOf("mock/free" to "high"),
            favoriteModels = setOf("mock/free")
        )
        eq(
            "profile variant/favorite roundtrip",
            withVariant,
            ServerProfile.fromJson(withVariant.toJson())
        )

        val withoutSecrets = ServerProfile.fromJson(server.toJson(includeSecrets = false))
        eq("ssh password hidden", "", withoutSecrets?.sshPassword)
        eq("api password hidden", "", withoutSecrets?.serverPassword)

        val broken = ServerProfile.fromJson(
            JSONObject().put("id", "s3").put("sshPort", 0).put("remotePort", 99999).put("mode", "???").put("sshUser", "")
        )
        eq("ssh port default", 22, broken?.sshPort)
        eq("opencode port default", 4096, broken?.remotePort)
        eq("mode default", ConnectionMode.TUNNEL, broken?.mode)
        eq("user default", "root", broken?.sshUser)
        check("missing id rejected") { ServerProfile.fromJson(JSONObject().put("name", "x")) == null }

        val direct = ServerProfile(id = "d", name = "d", color = 0, mode = ConnectionMode.DIRECT, directUrl = "https://x:4096")
        eq("direct display", "https://x:4096", direct.displayHost)
        eq("url normalization", "http://1.2.3.4:4096", UrlTools.normalize("1.2.3.4:4096"))
        eq("url trailing slash", "https://x.example", UrlTools.normalize("https://x.example/"))
        check("localhost is local") { UrlTools.isLocalUrl("http://127.0.0.1:4096") }
        check("private 10.x is local") { UrlTools.isLocalUrl("http://10.0.2.2:4096") }
        check("private 192.168.x is local") { UrlTools.isLocalUrl("http://192.168.1.5:4096") }
        check("private 172.16-31 is local") { UrlTools.isLocalUrl("http://172.20.0.1:4096") }
        check("external host is not local") { !UrlTools.isLocalUrl("http://example.com:4096") }
        check("external http is insecure") { UrlTools.isInsecureExternal("http://example.com:4096") }
        check("external https is secure") { !UrlTools.isInsecureExternal("https://example.com:4096") }

        val secureProfile = server.copy(
            sshPrivateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nkey\n-----END OPENSSH PRIVATE KEY-----",
            sshKeyPassphrase = "pass",
            allowInsecureHttp = true,
            tlsPin = "sha256/AAAA"
        )
        eq("profile secrets roundtrip", secureProfile, ServerProfile.fromJson(secureProfile.toJson()))
        eq(
            "profile secrets hidden",
            "",
            ServerProfile.fromJson(secureProfile.toJson(includeSecrets = false))?.sshPrivateKey
        )
    }

    /** Конфигурация ровно как на скриншоте из панели хостинга (V1-формат). */
    private fun providerConfigFromScreenshot() {
        val legacy = """
            {
              "provider": {
                "bobiviphouse": {
                  "npm": "@ai-sdk/openai-compatible",
                  "name": "BOBIVIPHOUSE",
                  "options": {
                    "baseURL": "https://77-91-115-251.sslip.io/api-store/v1",
                    "apiKey": "sk-cvc-9288ebc"
                  },
                  "models": { "gpt-6.1-sol": { "name": "gpt-6.1-sol" } }
                }
              },
              "model": "bobiviphouse/gpt-6.1-sol"
            }
        """.trimIndent()

        val (normalized, changes) = ProviderConfigV2.normalizeConfig(legacy)
        val root = JSONObject(normalized)
        check("v1 provider renamed") { !root.has("provider") && root.has("providers") }
        val entry = root.getJSONObject("providers").getJSONObject("bobiviphouse")
        eq("v1 npm → package", ProviderConfig.DEFAULT_PACKAGE, entry.getString("package"))
        eq(
            "v1 options → settings.baseURL",
            "https://77-91-115-251.sslip.io/api-store/v1",
            entry.getJSONObject("settings").getString("baseURL")
        )
        check("v1 apiKey kept") { entry.getJSONObject("settings").has("apiKey") }
        check("v1 model kept") { entry.getJSONObject("models").has("gpt-6.1-sol") }
        eq("v1 default model untouched", "bobiviphouse/gpt-6.1-sol", root.getString("model"))
        check("v1 changes reported") { changes.contains("bobiviphouse") }

        eq(
            "aisdk prefix for unknown",
            "aisdk:@acme/opencode-provider",
            ProviderConfigV2.normalizePackage("@acme/opencode-provider")
        )
        eq(
            "anthropic package mapping",
            "@opencode/ai/providers/anthropic",
            ProviderConfigV2.normalizePackage("@ai-sdk/anthropic")
        )
        eq(
            "openai-compatible responses",
            ProviderConfig.DEFAULT_PACKAGE + "/responses",
            ProviderConfigV2.normalizePackage("@ai-sdk/openai-compatible/responses")
        )
        eq(
            "native package kept",
            "@opencode/ai/providers/openrouter",
            ProviderConfigV2.normalizePackage("@opencode/ai/providers/openrouter")
        )

        val (removed, removedChanges) = ProviderConfigV2.removeProvider(normalized, "bobiviphouse")
        check("remove from providers") {
            JSONObject(removed).optJSONObject("providers")?.has("bobiviphouse") != true
        }
        check("remove reported") { removedChanges.contains("bobiviphouse") }
        eq("providers ids", listOf("bobiviphouse"), ProviderConfigV2.providerIds(normalized))
    }

    /** JSONC с комментариями и висячими запятыми. */
    private fun providerConfigLenient() {
        val jsonc = """
            {
              // Провайдер из инструкции
              "providers": {
                "gw": {
                  "package": "@opencode/ai/providers/openai-compatible",
                  "settings": { "baseURL": "https://gw.example/v1", /* ключ ниже */ },
                },
              },
              "model": "gw/some.model",
            }
        """.trimIndent()
        val parsed = runCatching { JSONObject(ProviderConfigV2.parseLenient(jsonc)) }
        check("jsonc parsed") { parsed.isSuccess }
        eq("jsonc model", "gw/some.model", parsed.getOrNull()?.optString("model"))
        check("jsonc provider") { parsed.getOrNull()?.optJSONObject("providers")?.has("gw") == true }
        check(
            "jsonc keeps slashes in strings"
        ) { parsed.getOrNull()?.optJSONObject("providers")?.optJSONObject("gw")
            ?.optJSONObject("settings")?.optString("baseURL") == "https://gw.example/v1" }

        val merged = ProviderConfig.merge(jsonc, CustomProvider("gw2", "GW2", "https://gw2/v1", "k"))
        check("merge into jsonc") { JSONObject(merged).getJSONObject("providers").has("gw2") }
    }

    /** `{env:…}` и `{file:…}` остаются как есть, если переменная не найдена. */
    private fun providerConfigSecure() {
        eq("env resolved", "secret", ProviderConfigV2.resolveEnv("{env:API_KEY}", mapOf("API_KEY" to "secret")))
        eq("env unresolved", "{env:API_KEY}", ProviderConfigV2.resolveEnv("{env:API_KEY}", emptyMap()))
        eq("plain key", "sk-1", ProviderConfigV2.resolveEnv("sk-1", emptyMap()))
        eq("dollar env", "v", ProviderConfigV2.resolveEnv("\${KEY}", mapOf("KEY" to "v")))
        eq("api key of provider", "sk-1", ProviderConfigV2.apiKeyOf(
            """{"providers":{"a":{"settings":{"apiKey":"sk-1"}}}}""",
            "a"
        ))
        eq("api key legacy", "", ProviderConfigV2.apiKeyOf("""{"providers":{}}""", "missing"))
    }

    private fun updateVersions() {
        check("newer patch") { VersionTools.isNewer("1.0.1", "1.0.0") }
        check("newer minor") { VersionTools.isNewer("1.1.0", "1.0.9") }
        check("newer major") { VersionTools.isNewer("2.0.0", "1.9.9") }
        check("same version") { !VersionTools.isNewer("1.0.0", "1.0.0") }
        check("older version") { !VersionTools.isNewer("0.9.9", "1.0.0") }
        check("v-prefix") { VersionTools.isNewer("v1.2.0", "1.1.0") }
        check("prerelease suffix") { VersionTools.isNewer("v2.0.0-beta", "1.9.9") }
    }

    private fun updateManifest() {
        val body = """
            {
              "version": "1.0.5",
              "name": "OpenCode Client by inFernan v1.0.5",
              "notes": "Заметки о релизе",
              "apk": "https://github.com/AmShaegar-dev/opencode-client-by-infernan/releases/download/v1.0.5/OpenCode-Client-by-inFernan-v1.0.5.apk",
              "page": "https://github.com/AmShaegar-dev/opencode-client-by-infernan/releases/tag/v1.0.5",
              "publishedAt": "2026-11-06T12:00:00Z"
            }
        """.trimIndent()

        val update = UpdateManifest.parse(body, "1.0.4")
        eq("manifest version", "1.0.5", update?.version)
        eq("manifest name", "OpenCode Client by inFernan v1.0.5", update?.name)
        eq("manifest notes", "Заметки о релизе", update?.notes)
        check("manifest apk url") { update?.assetUrl?.endsWith(".apk") == true }
        check("manifest same version ignored") { UpdateManifest.parse(body, "1.0.5") == null }
        check("manifest older ignored") { UpdateManifest.parse(body, "1.0.6") == null }
        check("manifest empty ignored") { UpdateManifest.parse("{}", "1.0.0") == null }
        check("manifest invalid ignored") { UpdateManifest.parse("not json", "1.0.0") == null }

        val derived = UpdateManifest.parse("""{"version":"v2.0.0"}""", "1.0.4")
        check("manifest derives apk url") {
            derived?.assetUrl?.contains("OpenCode-Client-by-inFernan-v2.0.0.apk") == true
        }
        check("manifest v-prefix stripped") { derived?.version == "2.0.0" }
    }

    private fun textCleaner() {
        eq("ansi stripped", "red", TextCleaner.clean("\u001B[31mred\u001B[0m"))
        eq("control stripped", "ab", TextCleaner.clean("a\u0007b"))
        eq("cp866 repaired", "умолчанию", TextCleaner.clean("╤Г╨╝╨╛╨╗╤З╨░╨╜╨╕╤О"))
        eq("tree kept", "└── src", TextCleaner.clean("└── src"))
        eq("replacement marked", "a?b", TextCleaner.clean("a\uFFFDb"))
        eq("fake replacement collapsed", "a?b", TextCleaner.clean("aя┐╜b"))
        eq("plain text kept", "Привет, мир!", TextCleaner.clean("Привет, мир!"))
    }

    private fun displayMerge() {
        val merged = ChatDisplay.merge(
            listOf(
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    text = "",
                    reasoning = "r1",
                    tools = listOf(ToolCall("shell", "completed", "echo", "ok")),
                    completed = true
                ),
                ChatMessage(
                    id = "a2",
                    role = "assistant",
                    text = "Итог",
                    reasoning = "r2",
                    tools = listOf(ToolCall("edit", "completed", "patch", "")),
                    completed = true
                ),
                ChatMessage(id = "u1", role = "user", text = "привет")
            )
        )
        eq("merged count", 2, merged.size)
        eq("merged reasoning", "r1\n\nr2", merged[0].message.reasoning)
        eq("merged text", "Итог", merged[0].message.text)
        eq("merged tools", 2, merged[0].message.tools.size)
        eq("merged sources", listOf("a1", "a2"), merged[0].sourceIds)
        eq("user stays separate", "u1", merged[1].message.id)

        val withHidden = ChatDisplay.merge(
            listOf(
                ChatMessage(id = "a1", role = "assistant", text = "", completed = true),
                ChatMessage(id = "h1", role = "hidden", text = "shell"),
                ChatMessage(id = "a2", role = "assistant", text = "ok", completed = true)
            )
        )
        eq("hidden skipped", 1, withHidden.size)
        eq("hidden bridges run", 2, withHidden[0].sourceIds.size)

        val single = ChatDisplay.merge(
            listOf(ChatMessage(id = "solo", role = "assistant", text = "ok", completed = true))
        )
        eq("single run stable id", "run_solo", single[0].message.id)
    }

    private fun passwords() {
        check("valid password") { Passwords.isValidServerPassword("Abc_123-xyz") }
        check("short password rejected") { !Passwords.isValidServerPassword("short") }
        check("space rejected") { !Passwords.isValidServerPassword("with space") }
        check("cyrillic rejected") { !Passwords.isValidServerPassword("кириллица1") }
        val generated = Passwords.generate()
        eq("generated length", 32, generated.length)
        check("generated valid") { Passwords.isValidServerPassword(generated) }
        val full = List(ServerColors.palette.size) { ServerProfile(id = "$it", name = "n", color = 0) }
        eq("color cycle", ServerColors.palette[0], ServerColors.next(full))
    }
}
