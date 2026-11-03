package ru.opencode.mobile

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.opencode.mobile.data.ChatJson
import ru.opencode.mobile.data.AgentInfo
import ru.opencode.mobile.data.AppUpdate
import ru.opencode.mobile.data.ChatMessage
import ru.opencode.mobile.data.CommandInfo
import ru.opencode.mobile.data.CustomProvider
import ru.opencode.mobile.data.FileDiffInfo
import ru.opencode.mobile.data.IntegrationInfo
import ru.opencode.mobile.data.IntegrationMethod
import ru.opencode.mobile.data.ModelInfo
import ru.opencode.mobile.data.ModelRef
import ru.opencode.mobile.data.OAuthAttemptInfo
import ru.opencode.mobile.data.ProviderConfig
import ru.opencode.mobile.data.ProviderConfigV2
import ru.opencode.mobile.data.ProviderInfo
import ru.opencode.mobile.data.PromptAttachment
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.data.ServerStatus
import ru.opencode.mobile.data.ServersStore
import ru.opencode.mobile.data.SessionInfo
import ru.opencode.mobile.data.UpdateChecker
import ru.opencode.mobile.data.UsageLimitsInfo
import ru.opencode.mobile.net.ConnectionHub
import ru.opencode.mobile.net.OpenCodeApi
import ru.opencode.mobile.net.ServerStorageCommands
import ru.opencode.mobile.net.Ssh
import ru.opencode.mobile.net.SshTarget
import ru.opencode.mobile.net.StorageInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Servers : Screen
    data class Editor(val serverId: String?) : Screen
    data class Install(val serverId: String? = null) : Screen
    data class Chats(val serverId: String) : Screen
    data class Chat(val serverId: String, val sessionId: String) : Screen
    data class Providers(val serverId: String) : Screen
    data class Backup(val serverId: String?) : Screen
    data object Settings : Screen
}

data class SessionsUi(
    val loading: Boolean = false,
    val items: List<SessionInfo> = emptyList(),
    val error: String = "",
    val query: String = ""
)

data class ChatUi(
    val serverId: String = "",
    val sessionId: String = "",
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val uploading: Boolean = false,
    val error: String = ""
)

data class StorageUi(
    val loading: Boolean = false,
    val info: StorageInfo? = null,
    val error: String = ""
)

data class UsageUi(
    val loading: Boolean = false,
    val info: UsageLimitsInfo? = null,
    val error: String = ""
)

data class DiffUi(
    val loading: Boolean = false,
    val items: List<FileDiffInfo> = emptyList(),
    val error: String = ""
)

data class UpdateUi(
    val checking: Boolean = false,
    val latest: AppUpdate? = null,
    val error: String = "",
    val lastChecked: Long = 0L,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val downloaded: java.io.File? = null,
    val currentVersion: String = ""
)

/** Состояние OAuth-входа: ссылка, ожидание ответа и ручной код. */
data class OAuthUi(
    val integrationId: String = "",
    val integrationName: String = "",
    val methodLabel: String = "",
    val attemptId: String = "",
    val url: String = "",
    val instructions: String = "",
    val mode: String = "auto",
    val status: String = "pending",
    val error: String = "",
    val starting: Boolean = true,
    val completing: Boolean = false,
    val bridgeActive: Boolean = false,
    val bridgeError: String = ""
) {
    val finished: Boolean get() = status == "complete" || status == "failed" || status == "expired"
    val codeRequired: Boolean get() = mode == "code"
}

/** Свой провайдер на сервере: описание из opencode.json. */
data class RemoteProvider(
    val id: String,
    val name: String,
    val packageName: String = "",
    val baseUrl: String = "",
    val models: List<String> = emptyList(),
    val hasKey: Boolean = false,
    val legacy: Boolean = false,
    /** Описание доступно, только если opencode.json прочитан по SSH. */
    val manageable: Boolean = true
)

data class ProvidersUi(
    val loading: Boolean = false,
    val providers: List<ProviderInfo> = emptyList(),
    val integrations: List<IntegrationInfo> = emptyList(),
    val custom: List<RemoteProvider> = emptyList(),
    val configPath: String = "",
    val configSource: String = "",
    val error: String = ""
)

data class ConfigDialogState(
    val path: String,
    val content: String,
    val editable: Boolean
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ServersStore(app)
    private val hub = ConnectionHub()

    var servers by mutableStateOf(store.read())
        private set

    var screen by mutableStateOf<Screen>(Screen.Servers)
        private set

    val statuses = mutableStateMapOf<String, ServerStatus>()

    var sessions by mutableStateOf(SessionsUi())
        private set

    var chat by mutableStateOf(ChatUi())
        private set

    var providers by mutableStateOf(ProvidersUi())
        private set

    /** Свободное место и размер кэша файлов на текущем сервере. */
    var storage by mutableStateOf(StorageUi())
        private set

    var usage by mutableStateOf(UsageUi())
        private set

    var uploads by mutableStateOf<List<String>>(emptyList())
        private set

    var uploadsLoading by mutableStateOf(false)
        private set

    var diffs by mutableStateOf(DiffUi())
        private set

    var commands by mutableStateOf<List<CommandInfo>>(emptyList())
        private set

    var agents by mutableStateOf<List<AgentInfo>>(emptyList())
        private set

    var commandsLoading by mutableStateOf(false)
        private set

    /** Состояние проверки обновлений GitHub. */
    var update by mutableStateOf(UpdateUi(currentVersion = BuildConfig.VERSION_NAME))
        private set

    /** Обновление, для которого нужно показать предложение установки. */
    var updatePrompt by mutableStateOf<AppUpdate?>(null)
        private set

    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set

    var selectedModel by mutableStateOf<ModelRef?>(null)
        private set

    var modelsLoading by mutableStateOf(false)
        private set

    var modelsError by mutableStateOf("")
        private set

    /** Ключи моделей, для которых сейчас пишется opencode.json. */
    var modelsUpdating by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Активный OAuth-вход; null — диалог закрыт. */
    var oauth by mutableStateOf<OAuthUi?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /** Содержимое opencode.json для просмотра и ручного редактирования. */
    var configDialog by mutableStateOf<ConfigDialogState?>(null)
        private set

    private val modelCache = mutableMapOf<String, List<ModelInfo>>()
    private var pollJob: Job? = null
    private var chatLoadJob: Job? = null
    private var oauthJob: Job? = null
    private var oauthBridge: Ssh.LocalBridge? = null

    /** Сериализует чтение/запись opencode.json: параллельные переключатели теряли изменения. */
    private val configMutex = Mutex()

    /** История экранов для системной кнопки «Назад». */
    private val backStack = ArrayDeque<Screen>()

    // ------------------------------------------------------------------
    // Навигация
    // ------------------------------------------------------------------

    private fun navigate(next: Screen) {
        if (screen == next) return
        backStack.addLast(screen)
        while (backStack.size > 32) backStack.removeFirst()
        screen = next
    }

    /** true — нажатие «Назад» должно вернуть на предыдущий экран, а не закрыть приложение. */
    fun canGoBack(): Boolean = backStack.isNotEmpty() || screen !is Screen.Servers

    /**
     * Возвращает на предыдущий экран. Если истории нет (например, после
     * сохранения сервера сразу открылся список чатов), подставляет
     * логический родительский экран. false — закрываем приложение.
     */
    fun goBack(): Boolean {
        val previous = backStack.removeLastOrNull()
        pollJob?.cancel()
        pollJob = null
        if (previous != null) {
            screen = previous
            if (previous is Screen.Chats) {
                chatLoadJob?.cancel()
                serverById(previous.serverId)?.let { refreshSessions(it) }
            }
            return true
        }

        // История пуста — ведём себя как кнопка «Назад» в настольной навигации.
        return when (val current = screen) {
            Screen.Servers -> false
            is Screen.Chats -> {
                backStack.clear()
                screen = Screen.Servers
                true
            }

            is Screen.Chat -> {
                backStack.clear()
                screen = Screen.Chats(current.serverId)
                chatLoadJob?.cancel()
                serverById(current.serverId)?.let { refreshSessions(it) }
                true
            }

            is Screen.Providers -> {
                backStack.clear()
                screen = Screen.Chats(current.serverId)
                serverById(current.serverId)?.let { refreshSessions(it) }
                true
            }

            is Screen.Editor, is Screen.Install, is Screen.Backup, Screen.Settings -> {
                backStack.clear()
                screen = Screen.Servers
                true
            }
        }
    }

    fun openServers() {
        backStack.clear()
        screen = Screen.Servers
    }

    fun openEditor(serverId: String?) {
        navigate(Screen.Editor(serverId))
    }

    fun openInstall(serverId: String? = null) {
        navigate(Screen.Install(serverId))
    }

    fun openChats(serverId: String) {
        val server = serverById(serverId) ?: return
        navigate(Screen.Chats(serverId))
        selectedModel = ModelRef.parse(server.defaultModel)?.withVariant(server)
        sessions = SessionsUi(loading = true)
        checkStatus(server)
        refreshSessions(server)
    }

    fun openChat(serverId: String, sessionId: String) {
        val server = serverById(serverId) ?: return
        navigate(Screen.Chat(serverId, sessionId))
        loadChat(server, sessionId)
    }

    fun openProviders(serverId: String) {
        val server = serverById(serverId) ?: return
        navigate(Screen.Providers(serverId))
        loadProviders(server)
    }

    fun openBackup(serverId: String?) {
        navigate(Screen.Backup(serverId))
    }

    fun openSettings() {
        navigate(Screen.Settings)
    }

    fun backFromChats() {
        if (!goBack()) {
            backStack.clear()
            screen = Screen.Servers
        }
    }

    fun backFromChat(serverId: String) {
        if (!goBack()) {
            backStack.clear()
            screen = Screen.Chats(serverId)
            pollJob?.cancel()
            serverById(serverId)?.let { refreshSessions(it) }
        }
    }

    fun serverById(id: String?): ServerProfile? =
        id?.let { target -> servers.firstOrNull { it.id == target } }

    fun statusOf(serverId: String): ServerStatus =
        statuses[serverId] ?: ServerStatus()

    fun selectedModelFor(server: ServerProfile): ModelRef? {
        val ref = selectedModel ?: ModelRef.parse(server.defaultModel)
            ?: modelCache[server.id]?.firstOrNull { it.enabled }?.ref
        return ref?.withVariant(server)
    }

    private fun ModelRef.withVariant(server: ServerProfile): ModelRef {
        if (!variant.isNullOrBlank()) return this
        val saved = server.variantByModel[key]?.takeIf { it.isNotBlank() } ?: return this
        return copy(variant = saved)
    }

    /** Модели, доступные для выбора (выключенные остаются в списке для управления). */
    val enabledModels: List<ModelInfo> get() = models.filter { it.enabled }

    // ------------------------------------------------------------------
    // Серверы
    // ------------------------------------------------------------------

    fun saveServer(profile: ServerProfile, navigate: Boolean = true) {
        val list = servers.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        servers = list
        store.write(list)

        hub.reset(profile.id)
        statuses[profile.id] = ServerStatus()
        selectedModel = ModelRef.parse(profile.defaultModel)
        modelCache.remove(profile.id)

        if (navigate) {
            // После сохранения сервера «Назад» должен вести к списку серверов,
            // а не обратно в редактор.
            backStack.clear()
            screen = Screen.Chats(profile.id)
            sessions = SessionsUi(loading = true)
            checkStatus(profile)
            refreshSessions(profile)
        }
    }

    fun updateServer(profile: ServerProfile) {
        val list = servers.map { if (it.id == profile.id) profile else it }
        servers = list
        store.write(list)
    }

    fun deleteServer(profile: ServerProfile) {
        hub.reset(profile.id)
        statuses.remove(profile.id)
        val list = servers.filterNot { it.id == profile.id }
        servers = list
        store.write(list)
        val current = screen
        val sameServer = when (current) {
            is Screen.Chats -> current.serverId == profile.id
            is Screen.Chat -> current.serverId == profile.id
            is Screen.Providers -> current.serverId == profile.id
            is Screen.Editor -> current.serverId == profile.id
            is Screen.Install -> current.serverId == profile.id
            else -> false
        }
        if (sameServer) openServers()
    }

    fun checkStatus(server: ServerProfile) {
        statuses[server.id] = statusOf(server.id).copy(checking = true, error = "")
        viewModelScope.launch {
            val result = io {
                try {
                    val info = hub.api(server).info()
                    ServerStatus(
                        online = true,
                        version = info.optString("version"),
                        checkedAt = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    ServerStatus(
                        online = false,
                        error = e.message ?: "Не удалось подключиться",
                        checkedAt = System.currentTimeMillis()
                    )
                }
            }
            statuses[server.id] = result
        }
    }

    fun refreshStatuses() {
        servers.forEach(::checkStatus)
    }

    fun refreshStorage(server: ServerProfile) {
        storage = StorageUi(loading = true)
        viewModelScope.launch {
            try {
                storage = StorageUi(info = io { readStorage(server) })
            } catch (e: Exception) {
                storage = StorageUi(error = humanError(e, "Не удалось узнать свободное место"))
            }
        }
    }

    fun clearUploadCache(server: ServerProfile, onResult: (Result<StorageInfo>) -> Unit) {
        viewModelScope.launch {
            storage = storage.copy(loading = true, error = "")
            try {
                val info = io {
                    val api = hub.api(server)
                    val cleared = runCatching { api.clearUploads() }
                    if (cleared.isFailure) {
                        val target = sshTarget(server)
                            ?: throw cleared.exceptionOrNull()
                                ?: IllegalStateException("Нет доступа к серверу")
                        Ssh.exec(target, server.sshFingerprint, ServerStorageCommands.CLEAR)
                    }
                    readStorage(server)
                }
                storage = StorageUi(info = info)
                onResult(Result.success(info))
            } catch (e: Exception) {
                storage = storage.copy(loading = false, error = humanError(e, "Не удалось очистить кэш"))
                onResult(Result.failure(e))
            }
        }
    }

    fun checkUpdatesIfDue() {
        val context = getApplication<Application>()
        if (!UpdateChecker.isDue(context)) {
            update = update.copy(lastChecked = UpdateChecker.lastChecked(context))
            return
        }
        checkUpdates(force = false)
    }

    fun checkUpdates(force: Boolean = true) {
        if (update.checking || update.downloading) return
        val context = getApplication<Application>()
        if (!force && !UpdateChecker.isDue(context)) return
        update = update.copy(checking = true, error = "")
        viewModelScope.launch {
            try {
                val latest = io { UpdateChecker.check(BuildConfig.VERSION_NAME) }
                UpdateChecker.markChecked(context)
                update = update.copy(
                    checking = false,
                    latest = latest,
                    error = "",
                    lastChecked = System.currentTimeMillis(),
                    downloaded = null,
                    progress = null
                )
                updatePrompt = latest?.takeIf { UpdateChecker.shouldPrompt(context, it) }
            } catch (e: Exception) {
                update = update.copy(
                    checking = false,
                    error = humanError(e, "Не удалось проверить обновления")
                )
            }
        }
    }

    fun dismissUpdatePrompt() {
        val current = updatePrompt ?: return
        UpdateChecker.ignore(getApplication(), current)
        updatePrompt = null
    }

    fun downloadUpdate(onResult: (Result<java.io.File>) -> Unit) {
        val latest = update.latest ?: updatePrompt ?: return
        if (update.downloading) return
        val context = getApplication<Application>()
        val target = java.io.File(
            java.io.File(context.cacheDir, "updates"),
            "opencode-client-${latest.version}.apk"
        )
        if (target.exists() && target.length() > 0) {
            update = update.copy(downloaded = target, progress = 1f, error = "")
            onResult(Result.success(target))
            return
        }
        update = update.copy(downloading = true, progress = 0f, error = "")
        viewModelScope.launch {
            try {
                var lastProgress = -1f
                val file = io {
                    UpdateChecker.download(latest.assetUrl, target) { percent ->
                        if (percent != null && (percent - lastProgress >= 0.01f || percent >= 1f)) {
                            lastProgress = percent
                            viewModelScope.launch(Dispatchers.Main) {
                                update = update.copy(progress = percent)
                            }
                        }
                    }
                }
                update = update.copy(
                    downloading = false,
                    progress = 1f,
                    downloaded = file,
                    error = ""
                )
                onResult(Result.success(file))
            } catch (e: Exception) {
                update = update.copy(
                    downloading = false,
                    progress = null,
                    error = humanError(e, "Не удалось скачать обновление")
                )
                onResult(Result.failure(e))
            }
        }
    }

    private suspend fun readStorage(server: ServerProfile): StorageInfo {
        val api = hub.api(server)
        val fromApi = runCatching { io { api.storageInfo() } }.getOrNull()
        if (fromApi != null && (fromApi.freeBytes != null || fromApi.uploadsBytes != null)) {
            return fromApi
        }
        val target = sshTarget(server)
            ?: return fromApi ?: StorageInfo(null, null)
        val output = Ssh.exec(target, server.sshFingerprint, ServerStorageCommands.INFO)
        return ServerStorageCommands.parse(output)
    }

    fun refreshUsage(server: ServerProfile) {
        usage = usage.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                usage = UsageUi(info = io { hub.api(server).usageLimits() })
            } catch (e: Exception) {
                usage = UsageUi(error = humanError(e, "Не удалось получить лимиты"))
            }
        }
    }

    fun loadUploads(server: ServerProfile) {
        uploadsLoading = true
        viewModelScope.launch {
            try {
                val list = io {
                    val api = hub.api(server)
                    runCatching { api.uploads() }.getOrElse { error ->
                        val target = sshTarget(server) ?: throw error
                        Ssh.exec(target, server.sshFingerprint, ServerStorageCommands.LIST)
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toList()
                    }
                }
                uploads = list.sorted()
            } catch (_: Exception) {
                uploads = emptyList()
            } finally {
                uploadsLoading = false
            }
        }
    }

    fun deleteUpload(server: ServerProfile, name: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                io {
                    val api = hub.api(server)
                    runCatching { api.deleteUpload(name) }.getOrElse { error ->
                        val target = sshTarget(server) ?: throw error
                        val safe = name.substringAfterLast('/').substringAfterLast('\\')
                        val command = "d=\"\$HOME/opencode-uploads\"; rm -f -- \"\$d\"/" +
                            "'" + safe.replace("'", "'\\''") + "'"
                        Ssh.exec(target, server.sshFingerprint, command)
                    }
                }
                loadUploads(server)
                refreshStorage(server)
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun toggleFavorite(server: ServerProfile, modelKey: String) {
        val favorite = server.favoriteModels.contains(modelKey)
        val updated = if (favorite) {
            server.copy(favoriteModels = server.favoriteModels - modelKey)
        } else {
            server.copy(favoriteModels = server.favoriteModels + modelKey)
        }
        updateServer(updated)
    }

    fun loadDiffs(server: ServerProfile) {
        val sessionId = chat.sessionId
        if (sessionId.isBlank()) return
        diffs = DiffUi(loading = true)
        viewModelScope.launch {
            try {
                val items = io { hub.api(server).diffs(sessionId) }
                diffs = DiffUi(items = items)
            } catch (e: Exception) {
                diffs = DiffUi(error = humanError(e, "Не удалось получить изменения"))
            }
        }
    }

    fun clearDiffs() {
        diffs = DiffUi()
    }

    fun loadCommandsAndAgents(server: ServerProfile) {
        if (commandsLoading) return
        commandsLoading = true
        viewModelScope.launch {
            try {
                val loaded = io { hub.api(server).commands() to hub.api(server).agents() }
                commands = loaded.first
                agents = loaded.second
            } catch (_: Exception) {
                commands = emptyList()
                agents = emptyList()
            } finally {
                commandsLoading = false
            }
        }
    }

    fun switchAgent(server: ServerProfile, agent: AgentInfo, onResult: (Result<Unit>) -> Unit) {
        val sessionId = chat.sessionId
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            try {
                io { hub.api(server).switchAgent(sessionId, agent.id) }
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /** Выполняет слэш-команду и обновляет чат после завершения. */
    fun runCommand(server: ServerProfile, raw: String) {
        val sessionId = chat.sessionId
        if (sessionId.isBlank()) return
        val body = raw.trim().removePrefix("/")
        val name = body.substringBefore(' ').trim()
        if (name.isBlank()) return
        val text = body.substringAfter(' ', "").trim()
        viewModelScope.launch {
            try {
                io { hub.api(server).runCommand(sessionId, name, text) }
                delay(800)
                val loaded = io { hub.api(server).messages(sessionId) }
                chat = chat.copy(messages = loaded, loading = false)
                refreshSessions(server)
            } catch (e: Exception) {
                chat = chat.copy(error = humanError(e, "Не удалось выполнить команду"))
            }
        }
    }

    // ------------------------------------------------------------------
    // Чаты
    // ------------------------------------------------------------------

    fun refreshSessions(server: ServerProfile, query: String = sessions.query) {
        sessions = sessions.copy(loading = true, error = "", query = query)
        viewModelScope.launch {
            try {
                val items = io { hub.api(server).sessions(query) }
                sessions = SessionsUi(loading = false, items = items, query = query)
            } catch (e: Exception) {
                sessions = sessions.copy(loading = false, error = e.message ?: "Ошибка загрузки")
            }
        }
    }

    fun createChat(server: ServerProfile, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            busy = true
            try {
                val available = modelsFor(server)
                val model = selectedModelFor(server) ?: available.firstOrNull()?.ref
                val session = io {
                    hub.api(server).createSession(
                        title = "Новый чат",
                        model = model
                    )
                }
                if (session.id.isBlank()) throw IllegalStateException("Сервер не вернул ID сессии")
                selectedModel = session.model ?: model
                navigate(Screen.Chat(server.id, session.id))
                loadChat(server, session.id)
                refreshSessions(server)
            } catch (e: Exception) {
                onError(e.message ?: "Не удалось создать чат")
            } finally {
                busy = false
            }
        }
    }

    fun deleteChat(server: ServerProfile, session: SessionInfo, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                io { hub.api(server).deleteSession(session.id) }
                onDone()
                refreshSessions(server)
            } catch (e: Exception) {
                sessions = sessions.copy(error = e.message ?: "Не удалось удалить чат")
            }
        }
    }

    fun renameChat(server: ServerProfile, sessionId: String, title: String) {
        viewModelScope.launch {
            try {
                io { hub.api(server).renameSession(sessionId, title) }
                if (chat.sessionId == sessionId) chat = chat.copy(title = title)
                refreshSessions(server)
            } catch (e: Exception) {
                chat = chat.copy(error = e.message ?: "Не удалось переименовать")
            }
        }
    }

    fun loadChat(server: ServerProfile, sessionId: String) {
        chatLoadJob?.cancel()
        chat = ChatUi(
            serverId = server.id,
            sessionId = sessionId,
            title = sessions.items.firstOrNull { it.id == sessionId }?.title.orEmpty(),
            loading = true
        )
        chatLoadJob = viewModelScope.launch {
            try {
                val session = sessions.items.firstOrNull { it.id == sessionId }
                val loadedModels = if (models.isEmpty()) {
                    io { loadAllModels(server) }
                } else {
                    models
                }
                models = loadedModels
                modelCache[server.id] = loadedModels
                selectedModel = session?.model
                    ?: ModelRef.parse(server.defaultModel)?.withVariant(server)
                    ?: loadedModels.firstOrNull { it.enabled }?.ref

                val loaded = io { hub.api(server).messages(sessionId) }
                chat = chat.copy(messages = loaded, loading = false, sending = pollJob?.isActive == true)
            } catch (e: Exception) {
                chat = chat.copy(error = e.message ?: "Не удалось загрузить чат", loading = false)
            }
        }
    }

    /**
     * Загружает включённые модели из `/api/model` и добавляет выключенные из
     * `opencode.json`, чтобы их можно было включить обратно после перезагрузки.
     */
    private suspend fun loadAllModels(
        server: ServerProfile,
        keepEnabledKeys: Set<String> = emptySet(),
        disabledOverride: List<ProviderConfigV2.DisabledModel>? = null
    ): List<ModelInfo> {
        val api = hub.api(server)
        val loaded = io { api.models() }
        var disabledKnown = disabledOverride != null
        val disabled = disabledOverride ?: runCatching { io { api.disabledModels() } }
            .onSuccess { disabledKnown = true }
            .getOrNull()
            .orEmpty()

        val previous = models.associateBy { it.ref.key }
        val result = LinkedHashMap<String, ModelInfo>()
        loaded.forEach { model -> result[model.ref.key] = model }

        disabled.forEach { entry ->
            val key = "${entry.providerID}/${entry.modelID}"
            val base = result[key] ?: previous[key]
            result[key] = base?.copy(enabled = false) ?: ModelInfo(
                ref = ModelRef(entry.modelID, entry.providerID),
                modelID = entry.modelID,
                name = entry.name.ifBlank { entry.modelID },
                enabled = false,
                free = false,
                contextLimit = 0,
                variants = emptyList()
            )
        }

        // Конфиг не прочитался — не теряем уже известные выключенные модели.
        if (!disabledKnown) {
            previous.values.filter { !it.enabled }.forEach { result.putIfAbsent(it.ref.key, it) }
        }
        // Только что включённая модель может ещё отсутствовать в /api/model.
        keepEnabledKeys.forEach { key ->
            if (!result.containsKey(key)) {
                previous[key]?.let { result[key] = it.copy(enabled = true) }
            }
        }
        return result.values.toList()
    }

    private suspend fun modelsFor(server: ServerProfile): List<ModelInfo> {
        modelCache[server.id]?.let { cached ->
            val enabled = cached.filter { it.enabled }
            if (enabled.isNotEmpty()) return enabled
        }
        val loaded = loadAllModels(server)
        if (models.isEmpty()) models = loaded
        modelCache[server.id] = loaded
        return loaded.filter { it.enabled }
    }

    fun reloadModels(server: ServerProfile) {
        modelsLoading = true
        modelsError = ""
        viewModelScope.launch {
            try {
                val loaded = io { loadAllModels(server) }
                models = loaded
                modelCache[server.id] = loaded
                if (loaded.none { it.enabled }) {
                    modelsError = "Сервер не вернул ни одной включённой модели"
                } else {
                    val current = selectedModel
                    if (current == null || loaded.none { it.enabled && it.ref.key == current.key }) {
                        selectedModel = ModelRef.parse(server.defaultModel)
                            ?.takeIf { ref -> loaded.any { it.enabled && it.ref.key == ref.key } }
                            ?: loaded.firstOrNull { it.enabled }?.ref
                    }
                }
            } catch (e: Exception) {
                modelsError = e.message ?: "Не удалось загрузить модели"
            } finally {
                modelsLoading = false
            }
        }
    }

    /**
     * Включает или выключает модель в `opencode.json` на сервере:
     * `providers.<id>.models.<model>.disabled = true`.
     */
    fun setModelEnabled(server: ServerProfile, model: ModelInfo, enabled: Boolean) {
        val modelKey = model.ref.key
        if (modelsUpdating.contains(modelKey)) return
        modelsUpdating = modelsUpdating + modelKey
        modelsError = ""
        viewModelScope.launch {
            try {
                val loaded = io {
                    configMutex.withLock {
                        val config = readServerConfig(server)
                        val (updated, changed) = ProviderConfigV2.setModelDisabled(
                            config.content,
                            model.ref.providerID,
                            model.ref.id,
                            disabled = !enabled,
                            name = model.name.ifBlank { model.title }
                        )
                        if (changed) {
                            val path = config.path.ifBlank { configPathFor(server) }
                            writeServerConfig(server, path, updated)
                            delay(1_000)
                            runCatching { hub.api(server).reload() }
                        }
                        // Один общий список: включённые из API + выключенные из конфига.
                        loadAllModels(
                            server = server,
                            keepEnabledKeys = setOf(modelKey),
                            disabledOverride = ProviderConfigV2.disabledModels(updated)
                        )
                    }
                }
                models = loaded
                modelCache[server.id] = loaded

                val current = selectedModel
                if (!enabled && current != null && current.key == modelKey) {
                    val next = loaded.firstOrNull { it.enabled }?.ref
                    if (next != null) selectModel(server, next) else selectedModel = null
                } else if (enabled && current == null) {
                    loaded.firstOrNull { it.enabled }?.ref?.let { selectModel(server, it) }
                }
            } catch (e: Exception) {
                modelsError = humanError(e, "Не удалось изменить модель")
            } finally {
                modelsUpdating = modelsUpdating - modelKey
            }
        }
    }

    fun selectModel(server: ServerProfile, model: ModelRef) {
        selectedModel = model
        val variants = server.variantByModel.toMutableMap()
        if (model.variant.isNullOrBlank()) variants.remove(model.key) else variants[model.key] = model.variant
        val updated = server.copy(
            defaultModel = model.key,
            variantByModel = variants
        )
        updateServer(updated)
        val sessionId = chat.sessionId
        if (sessionId.isNotBlank()) {
            viewModelScope.launch {
                runCatching { io { hub.api(server).switchModel(sessionId, model) } }
                    .onFailure { chat = chat.copy(error = it.message ?: "Не удалось сменить модель") }
            }
        }
    }

    fun send(server: ServerProfile, text: String, attachments: List<PromptAttachment> = emptyList()) {
        val value = text.trim()
        if (value.isEmpty() && attachments.isEmpty()) return
        val sessionId = chat.sessionId
        if (sessionId.isBlank()) {
            chat = chat.copy(error = "Чат ещё не создан")
            return
        }
        if (value.startsWith("/")) {
            runCommand(server, value)
            return
        }

        pollJob?.cancel()
        val baseline = chat.messages.map { it.id }.toSet()
        val optimistic = ChatMessage(
            id = "local-${System.currentTimeMillis()}",
            role = "user",
            text = value,
            created = System.currentTimeMillis(),
            files = attachments.map { it.name }
        )
        chat = chat.copy(
            messages = chat.messages + optimistic,
            sending = true,
            error = ""
        )

        pollJob = viewModelScope.launch {
            try {
                io { hub.api(server).prompt(sessionId, value, attachments) }
                var attempts = 0
                var stableTicks = 0
                var lastText = ""
                while (isActive) {
                    delay(1000)
                    attempts++
                    val loaded = io { hub.api(server).messages(sessionId) }
                    if (chat.sessionId != sessionId) break

                    chat = chat.copy(messages = loaded)

                    // Ответ ищем только среди сообщений, появившихся после отправки,
                    // иначе можно принять за ответ предыдущий завершённый ход.
                    val fresh = loaded.filterNot { it.id in baseline }
                    val answer = fresh.lastOrNull { it.isAssistant }
                    val finished = answer != null &&
                        (answer.completed || answer.error.isNotBlank())
                    if (finished) break
                    if (attempts > MAX_POLL_SECONDS) break

                    // Защита от «залипания»: сервер не присылает изменений слишком долго.
                    val progress = answer?.let { it.text.length.toString() + ":" + it.tools.size } ?: ""
                    if (progress == lastText) stableTicks++ else stableTicks = 0
                    lastText = progress
                    if (answer != null && stableTicks > STALL_SECONDS) {
                        chat = chat.copy(
                            error = "Ответ перестал обновляться. Нажмите «Обновить», чтобы перечитать чат."
                        )
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chat = chat.copy(error = humanError(e, "Не удалось отправить сообщение"))
            } finally {
                if (chat.sessionId == sessionId) chat = chat.copy(sending = false)
            }
        }
    }

    fun stop(server: ServerProfile) {
        val sessionId = chat.sessionId
        pollJob?.cancel()
        pollJob = null
        chat = chat.copy(sending = false)
        if (sessionId.isNotBlank()) {
            viewModelScope.launch {
                runCatching { io { hub.api(server).interrupt(sessionId) } }
            }
        }
    }

    /** Текст ошибки без служебных исключений корутин. */
    private fun humanError(e: Throwable, fallback: String): String {
        if (e is CancellationException) return "Запрос прерван"
        val text = e.message.orEmpty()
        return when {
            text.contains("cancel", ignoreCase = true) -> "Запрос прерван"
            text.isBlank() -> fallback
            else -> text
        }
    }

    // ------------------------------------------------------------------
    // Провайдеры
    // ------------------------------------------------------------------

    fun loadProviders(server: ServerProfile) {
        providers = providers.copy(loading = true, error = "")
        viewModelScope.launch {
            var providerList: List<ProviderInfo> = emptyList()
            var integrationList: List<IntegrationInfo> = emptyList()
            var apiError = ""
            var ourModels: List<ModelInfo> = emptyList()
            try {
                val result = io {
                    val api = hub.api(server)
                    if (models.isEmpty()) {
                        val loaded = runCatching { loadAllModels(server) }.getOrNull().orEmpty()
                        models = loaded
                        modelCache[server.id] = loaded
                        ourModels = loaded
                    } else {
                        ourModels = models
                    }
                    api.providers() to api.integrations()
                }
                providerList = result.first
                integrationList = result.second
            } catch (e: Exception) {
                apiError = e.message ?: "Не удалось загрузить провайдеров"
            }

            // Свои провайдеры: из opencode.json по SSH, иначе из профиля.
            val remoteInfo = io { readRemoteProviders(server) }
            val remote = remoteInfo?.first
            val configPath = remoteInfo?.second.orEmpty()
            val custom = if (remote != null) {
                remote.map { entry ->
                    val modelCount = ourModels.count { it.ref.providerID == entry.id }
                    if (modelCount > 0 && entry.models.isEmpty()) {
                        entry.copy(models = ourModels.filter { it.ref.providerID == entry.id }.map { it.ref.id })
                    } else {
                        entry
                    }
                }
            } else {
                server.customProviders.map { local ->
                    RemoteProvider(
                        id = local.id,
                        name = local.name.ifBlank { local.id },
                        packageName = local.packageName,
                        baseUrl = local.baseUrl,
                        models = local.models,
                        hasKey = local.apiKey.isNotBlank(),
                        legacy = false,
                        manageable = false
                    )
                }
            }

            providers = ProvidersUi(
                loading = false,
                providers = providerList,
                integrations = integrationList,
                custom = custom,
                configPath = configPath,
                error = if (providerList.isEmpty() && apiError.isNotBlank()) apiError else ""
            )
        }
    }

    private fun configPathFor(server: ServerProfile): String {
        val home = if (server.sshUser.isBlank() || server.sshUser == "root") "/root" else "/home/${server.sshUser}"
        return "$home/.config/opencode/opencode.json"
    }

    /**
     * Читает opencode.json сначала через HTTP API сервера (shell + fs),
     * затем — по SSH. Это важно на мобильных сетях, где порт 22 может быть
     * закрыт, а API на 4096/443 продолжает работать.
     */
    private suspend fun readServerConfig(server: ServerProfile): Ssh.RemoteConfig {
        val api = hub.api(server)
        val http = runCatching { io { api.readConfig() } }
        http.getOrNull()?.let { (path, content) ->
            return Ssh.RemoteConfig(path.ifBlank { configPathFor(server) }, content)
        }

        val target = sshTarget(server)
        if (target != null) {
            val ssh = runCatching { Ssh.readConfig(target, server.sshFingerprint) }
            ssh.getOrNull()?.let { return it }
            throw IllegalStateException(
                "Не удалось прочитать opencode.json.\n" +
                    "API: ${http.exceptionOrNull()?.message ?: "ошибка"}\n" +
                    "SSH: ${ssh.exceptionOrNull()?.message ?: "ошибка"}"
            )
        }
        throw IllegalStateException(
            http.exceptionOrNull()?.message ?: "Не удалось прочитать opencode.json"
        )
    }

    /** Пишет opencode.json через HTTP API, при неудаче — по SSH. */
    private suspend fun writeServerConfig(server: ServerProfile, path: String, content: String) {
        val api = hub.api(server)
        val http = runCatching { io { api.writeConfig(path, content) } }
        if (http.isSuccess) return

        val target = sshTarget(server)
        if (target != null) {
            val ssh = runCatching {
                Ssh.writeConfig(target, server.sshFingerprint, path, content)
            }
            if (ssh.isSuccess) return
            throw IllegalStateException(
                "Не удалось сохранить opencode.json.\n" +
                    "API: ${http.exceptionOrNull()?.message ?: "ошибка"}\n" +
                    "SSH: ${ssh.exceptionOrNull()?.message ?: "ошибка"}"
            )
        }
        throw IllegalStateException(
            http.exceptionOrNull()?.message ?: "Не удалось сохранить opencode.json"
        )
    }

    /** Читает провайдеров из opencode.json через API, при неудаче — по SSH. */
    private suspend fun readRemoteProviders(server: ServerProfile): Pair<List<RemoteProvider>, String>? {
        val config = runCatching { readServerConfig(server) }.getOrNull() ?: return null
        val path = config.path.ifBlank { configPathFor(server) }
        if (config.content.isBlank()) return emptyList<RemoteProvider>() to path
        val env = sshTarget(server)?.let { target ->
            runCatching { Ssh.readEnv(target, config.path) }.getOrNull()
        }.orEmpty()
        return try {
            val raw = ProviderConfigV2.parseLenient(config.content)
            val root = JSONObject(raw)
            val native = root.optJSONObject("providers")
            val legacy = root.optJSONObject("provider")
            val ids = buildList {
                native?.let { for (key in it.keys()) add(key) }
                legacy?.let { for (key in it.keys()) if (key !in this) add(key) }
            }
            val list = ids.map { id ->
                val isLegacy = native?.has(id) != true
                val entry = if (isLegacy) {
                    legacy!!.optJSONObject(id) ?: JSONObject()
                } else {
                    native!!.optJSONObject(id) ?: JSONObject()
                }
                val settings = entry.optJSONObject("settings") ?: entry.optJSONObject("options")
                val key = settings?.optString("apiKey").orEmpty()
                val resolvedKey = if (key.isBlank()) "" else ProviderConfigV2.resolveEnv(key, env)
                val models = entry.optJSONObject("models")?.let { map ->
                    buildList { for (m in map.keys()) add(m) }
                }.orEmpty()
                RemoteProvider(
                    id = id,
                    name = entry.optString("name").ifBlank { id },
                    packageName = entry.optString("package").ifBlank { entry.optString("npm") },
                    baseUrl = settings?.optString("baseURL").orEmpty().ifBlank { entry.optString("api") },
                    models = models,
                    hasKey = resolvedKey.isNotBlank(),
                    legacy = isLegacy || entry.has("npm") || entry.has("options"),
                    manageable = true
                )
            }
            list to path
        } catch (e: Exception) {
            null
        }
    }

    fun openConfigDialog(server: ServerProfile) {
        viewModelScope.launch {
            val loaded = io {
                val config = runCatching { readServerConfig(server) }.getOrNull()
                if (config != null && config.content.isNotBlank()) {
                    config.path to config.content
                } else {
                    configPathFor(server) to ""
                }
            }
            configDialog = ConfigDialogState(loaded.first, loaded.second, editable = true)
        }
    }

    fun closeConfigDialog() {
        configDialog = null
    }

    fun saveConfig(server: ServerProfile, content: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val message = io {
                    val text = content.trim()
                    if (text.isEmpty()) throw IllegalStateException("Файл конфигурации пуст")
                    val cleaned = try {
                        JSONObject(ProviderConfigV2.parseLenient(text)).toString(2) + "\n"
                    } catch (e: Exception) {
                        throw IllegalStateException("Это не корректный JSON: ${e.message}")
                    }
                    val path = configDialog?.path ?: configPathFor(server)
                    writeServerConfig(server, path, cleaned)
                    delay(600)
                    runCatching { hub.api(server).reload() }
                    adapterConfigToServer(server, cleaned)
                    "Конфигурация сохранена"
                }
                modelCache.remove(server.id)
                models = emptyList()
                configDialog = null
                loadProviders(server)
                onResult(Result.success(message))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /** Приводит `provider`/`npm`/`options` к нативному V2 прямо на сервере. */
    fun migrateConfig(server: ServerProfile, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val message = io {
                    val config = readServerConfig(server)
                    if (config.content.isBlank()) throw IllegalStateException("opencode.json не найден")
                    val (normalized, changes) = ProviderConfigV2.normalizeConfig(config.content)
                    if (changes.isEmpty()) return@io "Уже в формате V2 — изменений нет"
                    writeServerConfig(server, config.path, normalized)
                    delay(600)
                    runCatching { hub.api(server).reload() }
                    "Обновлено: ${changes.joinToString()}"
                }
                modelCache.remove(server.id)
                models = emptyList()
                loadProviders(server)
                onResult(Result.success(message))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Сохраняет переданный пользователем JSON-фрагмент конфигурации на сервер и
     * сразу проверяет его собственным клиентом.
     */
    fun importProviderJson(server: ServerProfile, json: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val message = io {
                    val snippet = normalizeSnippet(server, json)
                    val config = readServerConfig(server)
                    val base = if (config.content.isBlank()) {
                        JSONObject().put("\$schema", "https://opencode.ai/config.json")
                    } else {
                        runCatching { JSONObject(ProviderConfigV2.parseLenient(config.content)) }
                            .getOrElse {
                                throw IllegalStateException(
                                    "opencode.json не читается как JSON. Исправьте его в редакторе конфигурации."
                                )
                            }
                    }
                    applySnippet(base, snippet)
                    val merged = base.toString(2) + "\n"
                    val path = config.path.ifBlank { configPathFor(server) }
                    writeServerConfig(server, path, merged)
                    delay(700)
                    runCatching { hub.api(server).reload() }

                    val providerId = snippet.optJSONObject("providers")?.keys()?.asSequence()?.firstOrNull()
                        ?: snippet.keys().asSequence().firstOrNull()
                    val report = verifyProvider(server, providerId)
                    report
                }
                modelCache.remove(server.id)
                models = emptyList()
                loadProviders(server)
                onResult(Result.success(message))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Проверка своего провайдера: ключ и endpoint сохраняются в opencode.json,
     * затем приложение само обращается к `baseURL` теми же моделями.
     */
    fun createProvider(
        server: ServerProfile,
        provider: CustomProvider,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val message = io {
                    val config = readServerConfig(server)
                    val merged = ProviderConfig.merge(config.content, provider)
                    writeServerConfig(server, config.path, merged)
                    delay(700)
                    runCatching { hub.api(server).reload() }
                    verifyProvider(server, provider.id)
                }
                val updated = server.copy(
                    customProviders = server.customProviders.filterNot { it.id == provider.id } + provider
                )
                updateServer(updated)
                modelCache.remove(server.id)
                models = emptyList()
                loadProviders(updated)
                onResult(Result.success(message))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun addCustomProvider(
        server: ServerProfile,
        provider: CustomProvider,
        onResult: (Result<Unit>) -> Unit
    ) {
        createProvider(server, provider) { result ->
            onResult(result.map { })
        }
    }

    fun removeCustomProvider(
        server: ServerProfile,
        provider: RemoteProvider,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                io {
                    val config = readServerConfig(server)
                    if (config.content.isNotBlank()) {
                        val (updated, _) = ProviderConfigV2.removeProvider(config.content, provider.id)
                        writeServerConfig(server, config.path, updated)
                    }
                    delay(500)
                    runCatching { hub.api(server).reload() }
                }
                val updated = server.copy(
                    customProviders = server.customProviders.filterNot { it.id == provider.id }
                )
                updateServer(updated)
                modelCache.remove(server.id)
                models = emptyList()
                loadProviders(updated)
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /** Заменяет ключ в записи провайдера на сервере. */
    fun setProviderKey(
        server: ServerProfile,
        providerId: String,
        key: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val message = io {
                    val config = readServerConfig(server)
                    val root = JSONObject(ProviderConfigV2.parseLenient(config.content))
                    var entry = root.optJSONObject("providers")?.optJSONObject(providerId)
                    var legacy = false
                    if (entry == null) {
                        entry = root.optJSONObject("provider")?.optJSONObject(providerId)
                        legacy = entry != null
                    }
                    if (entry == null) throw IllegalStateException("Провайдер $providerId не найден в конфигурации")
                    val settings = entry.optJSONObject("settings")
                        ?: if (legacy) entry.optJSONObject("options") else null
                        ?: JSONObject().also { entry.put("settings", it) }
                    settings.put("apiKey", key.trim())
                    writeServerConfig(server, config.path, root.toString(2) + "\n")
                    delay(600)
                    runCatching { hub.api(server).reload() }
                    verifyProvider(server, providerId)
                }
                modelCache.remove(server.id)
                models = emptyList()
                loadProviders(server)
                onResult(Result.success(message))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun connectKey(
        server: ServerProfile,
        provider: ProviderInfo,
        key: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                io {
                    val api = hub.api(server)
                    val integrations = providers.integrations.ifEmpty { api.integrations() }
                    val target = integrations.firstOrNull {
                        it.id == provider.integrationID || it.id == provider.id
                    }
                    val integrationId = target?.id
                        ?: provider.integrationID.takeIf { it.isNotBlank() }
                        ?: provider.id
                    api.connectKey(integrationId, key, provider.name)
                }
                modelCache.remove(server.id)
                models = emptyList()
                loadProviders(server)
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Начинает OAuth-вход (в том числе «Войти через OpenCode» для OpenCode Go).
     * Если провайдер возвращает callback на localhost, приложение пробрасывает
     * этот порт через SSH, чтобы браузер на телефоне смог завершить вход.
     */
    fun startOAuth(
        server: ServerProfile,
        integration: IntegrationInfo,
        method: IntegrationMethod
    ) {
        oauthJob?.cancel()
        closeOAuthBridge()
        val initial = OAuthUi(
            integrationId = integration.id,
            integrationName = integration.name,
            methodLabel = method.label,
            starting = true
        )
        oauth = initial
        oauthJob = viewModelScope.launch {
            try {
                val attempt = io {
                    hub.api(server).startOAuth(
                        integrationId = integration.id,
                        methodId = method.id.ifBlank { method.type },
                        label = integration.name
                    )
                }
                if (attempt.attemptId.isBlank()) {
                    throw IllegalStateException("Сервер не вернул идентификатор попытки входа")
                }
                val bridgeError = openOAuthBridge(server, attempt.url)
                oauth = initial.copy(
                    attemptId = attempt.attemptId,
                    url = attempt.url,
                    instructions = attempt.instructions,
                    mode = attempt.mode,
                    starting = false,
                    bridgeActive = oauthBridge != null,
                    bridgeError = bridgeError
                )
                pollOAuth(server, integration.id, attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeOAuthBridge()
                oauth = initial.copy(
                    starting = false,
                    status = "failed",
                    error = humanError(e, "Не удалось начать вход")
                )
            }
        }
    }

    private suspend fun pollOAuth(
        server: ServerProfile,
        integrationId: String,
        attempt: OAuthAttemptInfo
    ) {
        var elapsed = 0
        while (currentCoroutineContext().isActive) {
            delay(2_000)
            elapsed += 2
            val (status, message) = io {
                hub.api(server).oauthStatus(integrationId, attempt.attemptId)
            }
            when (status) {
                "complete" -> {
                    finishOAuth(server, attempt.attemptId)
                    return
                }

                "failed", "expired" -> {
                    closeOAuthBridge()
                    oauth = oauth?.copy(
                        status = status,
                        error = message.ifBlank {
                            if (status == "expired") "Ссылка для входа истекла. Начните заново."
                            else "Сервер отклонил вход"
                        }
                    )
                    return
                }

                else -> {
                    if (oauth?.status != status) oauth = oauth?.copy(status = status, error = "")
                }
            }
            if (elapsed >= OAUTH_WAIT_SECONDS) {
                closeOAuthBridge()
                oauth = oauth?.copy(
                    status = "expired",
                    error = "Не дождались подтверждения входа. Можно открыть ссылку ещё раз."
                )
                return
            }
        }
    }

    fun submitOAuthCode(server: ServerProfile, code: String) {
        val state = oauth ?: return
        if (state.attemptId.isBlank()) return
        if (code.isBlank()) {
            oauth = state.copy(error = "Введите код из браузера")
            return
        }
        oauthJob?.cancel()
        oauth = state.copy(completing = true, error = "")
        oauthJob = viewModelScope.launch {
            try {
                io {
                    hub.api(server).completeOAuth(state.integrationId, state.attemptId, code)
                }
                var elapsed = 0
                while (isActive && elapsed < 90) {
                    delay(1_000)
                    elapsed++
                    val (status, message) = io {
                        hub.api(server).oauthStatus(state.integrationId, state.attemptId)
                    }
                    when (status) {
                        "complete" -> {
                            finishOAuth(server, state.attemptId)
                            return@launch
                        }

                        "failed", "expired" -> {
                            closeOAuthBridge()
                            oauth = oauth?.copy(
                                status = status,
                                completing = false,
                                error = message.ifBlank { "Сервер отклонил код" }
                            )
                            return@launch
                        }
                    }
                }
                oauth = oauth?.copy(
                    completing = false,
                    error = "Код отправлен, но сервер не подтвердил вход. Проверьте подключение."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                oauth = oauth?.copy(
                    completing = false,
                    error = humanError(e, "Не удалось подтвердить код")
                )
            }
        }
    }

    fun cancelOAuth(server: ServerProfile) {
        val state = oauth
        oauthJob?.cancel()
        oauthJob = null
        oauth = null
        closeOAuthBridge()
        if (state != null && state.attemptId.isNotBlank()) {
            viewModelScope.launch {
                runCatching {
                    io { hub.api(server).cancelOAuth(state.integrationId, state.attemptId) }
                }
            }
        }
    }

    /** Убирает сохранённый аккаунт провайдера (ключ или OAuth-токен). */
    fun removeConnection(
        server: ServerProfile,
        connectionId: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (connectionId.isBlank()) {
            onResult(Result.failure(IllegalStateException("Это подключение задано на сервере и не удаляется из приложения")))
            return
        }
        viewModelScope.launch {
            try {
                io { hub.api(server).removeCredential(connectionId) }
                modelCache.remove(server.id)
                models = emptyList()
                loadProviders(server)
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    private suspend fun openOAuthBridge(server: ServerProfile, url: String): String {
        val port = oauthCallbackPort(url) ?: return ""
        val target = sshTarget(server)
            ?: return "SSH не настроен: если браузер не вернёт код автоматически, вставьте его вручную."
        return try {
            val bridge = Ssh.LocalBridge(target, server.sshFingerprint, port, port)
            io { bridge.start() }
            oauthBridge = bridge
            ""
        } catch (e: Exception) {
            "Не удалось пробросить callback-порт $port: ${e.message ?: "ошибка"}"
        }
    }

    private fun closeOAuthBridge() {
        runCatching { oauthBridge?.close() }
        oauthBridge = null
    }

    /** Порт из `redirect_uri`, если провайдер ждёт callback на localhost. */
    private fun oauthCallbackPort(url: String): Int? {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val redirect = parsed.getQueryParameter("redirect_uri") ?: return null
        val callback = runCatching { Uri.parse(redirect) }.getOrNull() ?: return null
        val scheme = callback.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = callback.host?.lowercase() ?: return null
        if (host !in setOf("127.0.0.1", "localhost", "::1")) return null
        return callback.port.takeIf { it in 1024..65535 }
    }

    private fun finishOAuth(server: ServerProfile, attemptId: String) {
        if (oauth?.attemptId != attemptId) return
        closeOAuthBridge()
        oauth = oauth?.copy(status = "complete", starting = false, completing = false, error = "")
        modelCache.remove(server.id)
        models = emptyList()
        loadProviders(server)
        viewModelScope.launch {
            delay(1_600)
            if (oauth?.attemptId == attemptId) oauth = null
        }
    }

    /**
     * Загружает файл на сервер и возвращает путь. Сначала пробует SFTP,
     * затем HTTP API сервера (работает без отдельного SSH-подключения).
     * В отличие от вложения в сообщении, файл остаётся в проекте.
     */
    fun uploadToServer(server: ServerProfile, uri: Uri, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            chat = chat.copy(uploading = true, error = "")
            try {
                val name = io { Attachments.displayName(getApplication(), uri) } ?: "file"
                val size = io { Attachments.sizeOf(getApplication(), uri) } ?: -1L
                if (size > MAX_UPLOAD_BYTES) {
                    throw IllegalStateException(
                        "Файл больше ${Attachments.humanSize(MAX_UPLOAD_BYTES)}. " +
                            "Загрузите его на сервер вручную или разбейте на части."
                    )
                }
                fun openStream() = getApplication<Application>().contentResolver
                    .openInputStream(uri)
                    ?: throw IllegalStateException("Не удалось открыть файл")

                var sshError: String? = null
                val target = sshTarget(server)
                if (target != null) {
                    val ssh = runCatching {
                        io {
                            Ssh.uploadFile(target, server.sshFingerprint, "", name) {
                                openStream()
                            }
                        }
                    }
                    ssh.getOrNull()?.let { path ->
                        onResult(Result.success(path))
                        return@launch
                    }
                    sshError = ssh.exceptionOrNull()?.message
                }

                val http = runCatching {
                    io { hub.api(server).uploadFile("", name, size) { openStream() } }
                }
                http.getOrNull()?.let { path ->
                    onResult(Result.success(path))
                    return@launch
                }
                val detail = listOfNotNull(
                    sshError?.let { "SSH: $it" },
                    http.exceptionOrNull()?.message?.let { "API: $it" }
                ).joinToString("\n")
                onResult(
                    Result.failure(
                        IllegalStateException(
                            detail.ifBlank { "Не удалось загрузить файл на сервер" }
                        )
                    )
                )
            } catch (e: Exception) {
                onResult(Result.failure(e))
            } finally {
                chat = chat.copy(uploading = false)
            }
        }
    }

    /**
     * Проверяет провайдера напрямую: берёт ключ и baseURL из конфигурации,
     * запрашивает список моделей по OpenAI-совместимому `/models`.
     */
    private suspend fun verifyProvider(server: ServerProfile, providerId: String?): String {
        if (providerId.isNullOrBlank()) return "Конфигурация сохранена"
        val config = runCatching { readServerConfig(server) }.getOrNull()
            ?: return "Конфигурация сохранена"
        val env = sshTarget(server)?.let { target ->
            runCatching { Ssh.readEnv(target, config.path) }.getOrNull()
        }.orEmpty()
        val root = runCatching { JSONObject(ProviderConfigV2.parseLenient(config.content)) }.getOrNull()
            ?: return "Конфигурация сохранена"
        val entry = root.optJSONObject("providers")?.optJSONObject(providerId)
            ?: root.optJSONObject("provider")?.optJSONObject(providerId)
            ?: return "Конфигурация сохранена"
        val settings = entry.optJSONObject("settings") ?: entry.optJSONObject("options")
        val baseUrl = settings?.optString("baseURL").orEmpty().ifBlank { entry.optString("api") }
        val rawKey = settings?.optString("apiKey").orEmpty()
        val key = if (rawKey.isBlank()) "" else ProviderConfigV2.resolveEnv(rawKey, env)
        if (baseUrl.isBlank()) {
            return "Провайдер $providerId сохранён, но без baseURL"
        }
        if (key.isBlank()) {
            return "Провайдер $providerId сохранён без ключа"
        }
        val result = withContext(Dispatchers.IO) { hub.checkOpenAiCompatible(baseUrl, key) }
        return if (result.first) {
            "Провайдер $providerId работает: ${result.second} моделей"
        } else {
            "Провайдер $providerId сохранён, но проверка не прошла: ${result.third}"
        }
    }

    /**
     * Фрагмент конфигурации из буфера/инструкции: `{ "providers": {…} }`,
     * `{ "provider": {…} }` или просто `{ "id": {…} }`.
     */
    private fun normalizeSnippet(server: ServerProfile, json: String): JSONObject {
        val cleaned = ProviderConfigV2.parseLenient(json.trim())
        val root = try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            throw IllegalStateException("Это не корректный JSON: ${e.message}")
        }
        val (normalized, _) = ProviderConfigV2.normalizeConfig(root.toString())
        val parsed = JSONObject(normalized)
        val native = parsed.optJSONObject("providers")
        if (native != null) {
            if (native.length() == 0) throw IllegalStateException("В конфигурации нет провайдеров")
            return JSONObject().put("providers", native)
        }
        // Голый набор провайдеров
        if (parsed.length() > 0) {
            val providers = JSONObject()
            for (id in parsed.keys()) {
                val entry = parsed.optJSONObject(id) ?: continue
                providers.put(id, ProviderConfigV2.normalizeProvider(entry))
            }
            if (providers.length() > 0) {
                return JSONObject().put("providers", providers)
            }
        }
        throw IllegalStateException("Не найдено ни одного провайдера")
    }

    private fun applySnippet(root: JSONObject, snippet: JSONObject) {
        val providers = root.optJSONObject("providers") ?: JSONObject().also { root.put("providers", it) }
        val incoming = snippet.optJSONObject("providers") ?: JSONObject()
        for (id in incoming.keys()) {
            providers.put(id, incoming.get(id))
        }
        if (!root.has("\$schema")) root.put("\$schema", "https://opencode.ai/config.json")
    }

    /** Сохраняет параметры последнего своего провайдера в профиль (для бэкапа). */
    private fun adapterConfigToServer(server: ServerProfile, config: String) {
        val root = runCatching { JSONObject(ProviderConfigV2.parseLenient(config)) }.getOrNull() ?: return
        val providers = root.optJSONObject("providers") ?: return
        val list = mutableListOf<CustomProvider>()
        for (id in providers.keys()) {
            val entry = providers.optJSONObject(id) ?: continue
            val settings = entry.optJSONObject("settings")
            list += CustomProvider(
                id = id,
                name = entry.optString("name").ifBlank { id },
                baseUrl = settings?.optString("baseURL").orEmpty(),
                apiKey = settings?.optString("apiKey").orEmpty(),
                packageName = entry.optString("package").ifBlank { ProviderConfig.DEFAULT_PACKAGE },
                models = entry.optJSONObject("models")?.let { models ->
                    buildList { for (m in models.keys()) add(m) }
                }.orEmpty()
            )
        }
        updateServer(server.copy(customProviders = list))
    }

    // ------------------------------------------------------------------
    // Импорт и экспорт
    // ------------------------------------------------------------------

    fun exportChat(server: ServerProfile, sessionId: String, uri: Uri, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val json = io { hub.api(server).exportSession(sessionId) }
                val payload = JSONObject(json).optJSONObject("data") ?: JSONObject(json)
                val root = JSONObject().apply {
                    put("app", "opencode-mobile")
                    put("type", "sessions")
                    put("version", 1)
                    put("exportedAt", isoNow())
                    put("server", server.name)
                    put("sessions", JSONArray().put(payload))
                }
                io { writeFile(uri, root.toString(2)) }
                onResult(Result.success("Чат экспортирован"))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun exportAllChats(server: ServerProfile, uri: Uri, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val message = io {
                    val api = hub.api(server)
                    val list = api.sessions()
                    val array = JSONArray()
                    var failed = 0
                    list.forEach { session ->
                        try {
                            val exported = JSONObject(api.exportSession(session.id))
                            array.put(exported.optJSONObject("data") ?: exported)
                        } catch (e: Exception) {
                            failed++
                        }
                    }
                    val root = JSONObject().apply {
                        put("app", "opencode-mobile")
                        put("type", "sessions")
                        put("version", 1)
                        put("exportedAt", isoNow())
                        put("server", server.name)
                        put("sessions", array)
                    }
                    writeFile(uri, root.toString(2))
                    if (failed > 0) "Экспортировано ${array.length()}, ошибок: $failed"
                    else "Экспортировано чатов: ${array.length()}"
                }
                onResult(Result.success(message))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun importChats(server: ServerProfile, uri: Uri, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val message = io {
                    val text = readFile(uri)
                    val sessions = parseSessionsFile(text)
                    if (sessions.isEmpty()) throw IllegalStateException("В файле нет сессий")
                    val api = hub.api(server)
                    var ok = 0
                    val errors = mutableListOf<String>()
                    sessions.forEach { item ->
                        try {
                            api.importSession(item)
                            ok++
                        } catch (e: Exception) {
                            errors += (e.message ?: "ошибка")
                        }
                    }
                    if (ok == 0) {
                        throw IllegalStateException(
                            "Не удалось импортировать: ${errors.firstOrNull() ?: "неизвестная ошибка"}"
                        )
                    }
                    if (errors.isEmpty()) "Импортировано чатов: $ok"
                    else "Импортировано: $ok, ошибок: ${errors.size}"
                }
                refreshSessions(server)
                onResult(Result.success(message))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun exportBackup(uri: Uri, includeSecrets: Boolean, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val root = JSONObject().apply {
                    put("app", "opencode-mobile")
                    put("type", "backup")
                    put("version", 1)
                    put("exportedAt", isoNow())
                    put("includesSecrets", includeSecrets)
                    put("servers", JSONArray().apply {
                        servers.forEach { put(it.toJson(includeSecrets)) }
                    })
                }
                io { writeFile(uri, root.toString(2)) }
                onResult(Result.success("Резервная копия сохранена (${servers.size} серверов)"))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun importBackup(uri: Uri, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val count = io {
                    val root = JSONObject(readFile(uri))
                    val array = root.optJSONArray("servers")
                        ?: throw IllegalStateException("Это не резервная копия OpenCode Mobile")
                    var added = 0
                    val list = servers.toMutableList()
                    for (i in 0 until array.length()) {
                        val profile = array.optJSONObject(i)?.let { ServerProfile.fromJson(it) } ?: continue
                        val index = list.indexOfFirst { it.id == profile.id || it.name == profile.name }
                        if (index >= 0) list[index] = profile else list.add(profile)
                        added++
                    }
                    servers = list
                    store.write(list)
                    added
                }
                onResult(Result.success("Загружено серверов: $count"))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun suggestedFileName(prefix: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        return "${prefix}_$stamp.json"
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private fun sshTarget(server: ServerProfile): SshTarget? {
        if (server.sshHost.isBlank()) return null
        if (server.sshPassword.isBlank() && server.sshPrivateKey.isBlank()) return null
        return SshTarget(
            host = server.sshHost,
            port = server.sshPort,
            user = server.sshUser.ifBlank { "root" },
            password = server.sshPassword,
            privateKey = server.sshPrivateKey,
            keyPassphrase = server.sshKeyPassphrase
        )
    }

    private fun parseSessionsFile(text: String): List<JSONObject> {
        val trimmed = text.trim()
        val array: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                val data = root.optJSONObject("data")
                when {
                    root.has("sessions") -> root.getJSONArray("sessions")
                    root.has("info") && root.has("messages") -> JSONArray().put(root)
                    data != null && data.has("info") && data.has("messages") ->
                        JSONArray().put(data)
                    else -> throw IllegalStateException("Неизвестный формат файла с чатами")
                }
            }
        }
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(::add)
            }
        }
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

    private fun writeFile(uri: Uri, text: String) {
        val resolver = getApplication<Application>().contentResolver
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Не удалось открыть файл для записи")
    }

    private fun readFile(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Не удалось открыть файл")
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        chatLoadJob?.cancel()
        oauthJob?.cancel()
        closeOAuthBridge()
        hub.closeAll()
    }

    private companion object {
        const val MAX_POLL_SECONDS = 900
        const val STALL_SECONDS = 120
        const val OAUTH_WAIT_SECONDS = 600
        const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024
    }
}
