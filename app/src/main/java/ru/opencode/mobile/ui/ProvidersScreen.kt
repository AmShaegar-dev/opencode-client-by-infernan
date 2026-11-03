package ru.opencode.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.RemoteProvider
import ru.opencode.mobile.data.CustomProvider
import ru.opencode.mobile.data.IntegrationConnection
import ru.opencode.mobile.data.IntegrationInfo
import ru.opencode.mobile.data.ProviderConfig
import ru.opencode.mobile.data.ProviderInfo
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Green
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.Surface2
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

/** Пример «ручного» провайдера из инструкций панелей хостинга. */
private const val MANUAL_EXAMPLE = """{
  "providers": {
    "my-gateway": {
      "name": "My Gateway",
      "package": "@opencode/ai/providers/openai-compatible",
      "settings": {
        "baseURL": "https://example.com/api/v1",
        "apiKey": "sk-..."
      },
      "models": {
        "gpt-6.1-sol": { "name": "gpt-6.1-sol" }
      }
    }
  },
  "model": "my-gateway/gpt-6.1-sol"
}"""

@Composable
fun ProvidersScreen(vm: AppViewModel, server: ServerProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = vm.providers

    var keyTarget by remember { mutableStateOf<ProviderInfo?>(null) }
    var keyValue by remember { mutableStateOf("") }
    var keyBusy by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf("") }

    /** Провайдер, для которого открыт выбор способа входа. */
    var authTarget by remember { mutableStateOf<ProviderInfo?>(null) }
    var connectionTarget by remember { mutableStateOf<Pair<ProviderInfo, IntegrationConnection>?>(null) }

    var showAdd by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var configTarget by remember { mutableStateOf<RemoteProvider?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteProvider?>(null) }

    val configDialog = vm.configDialog

    /** Выбранные провайдеры, которые ещё не записаны на сервер. */
    var pending by remember(server.id) { mutableStateOf(listOf<PendingProvider>()) }

    /** Ключ из модального окна, которое сейчас открыто. */

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val type = context.contentResolver.getType(uri).orEmpty()
            val name = uri.lastPathSegment.orEmpty()
            if (type.startsWith("image/") || name.endsWith(".png", true) ||
                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
            ) {
                Toast.makeText(
                    context,
                    "Это изображение. Нажмите «Добавить провайдера», чтобы ввести данные вручную.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val snippet = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                val parsed = snippet?.let { PendingProvider.fromJson(it) }
                when {
                    snippet.isNullOrBlank() -> toast("Не удалось прочитать файл")
                    parsed.isNullOrEmpty() -> toast("В файле не найден объект с провайдерами")
                    else -> pending = pending + parsed
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        AppHeader(
            title = "Провайдеры",
            subtitle = server.name,
            onBack = { vm.openChats(server.id) },
            actions = {
                IconButton(onClick = { vm.loadProviders(server) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = TextMuted)
                }
            }
        )

        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Бесплатные модели работают без ключа. Для платных провайдеров добавьте API-ключ " +
                        "или войдите через браузер: OpenCode Go, Anthropic и другие интеграции " +
                        "подключаются на сервере, поэтому вход сохраняется между устройствами.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            if (state.loading && state.providers.isEmpty() && state.custom.isEmpty()) {
                item { LoadingLine("Загружаем провайдеров…") }
            }

            if (state.error.isNotBlank()) {
                item {
                    Surface(
                        color = Color(0xFF2A1414),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(state.error, color = Red, fontSize = 12.sp, lineHeight = 16.sp)
                            TextButton(onClick = { vm.loadProviders(server) }) {
                                Text("Повторить", color = Accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (state.custom.any { it.legacy }) {
                item {
                    Surface(
                        color = Color(0xFF2B2410),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Найдена конфигурация V1 (provider / npm / options).",
                                    color = TextMain,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "Приложение понимает её, но для V2 лучше привести к новому формату.",
                                    color = TextDim,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                            TextButton(onClick = {
                                vm.migrateConfig(server) { result ->
                                    result.onSuccess(::toast)
                                        .onFailure { toast(it.message ?: "Не удалось обновить") }
                                }
                            }) {
                                Text("Исправить", color = Accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (state.providers.isNotEmpty()) {
                item {
                    SectionTitle("Провайдеры сервера")
                }
                items(state.providers, key = { "api-" + it.id }) { provider ->
                    val integration = state.integrations.firstOrNull {
                        it.id == provider.integrationID || it.id == provider.id
                    }
                    ProviderRow(
                        provider = provider,
                        integration = integration,
                        canConnect = integration != null ||
                            (provider.integrationID.isNotBlank() && state.integrations.isEmpty()),
                        onClick = {
                            keyError = ""
                            if (integration != null) authTarget = provider else keyTarget = provider
                        }
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Свои провайдеры")
                Text(
                    if (state.configPath.isNotBlank()) {
                        "opencode.json: ${state.configPath}"
                    } else {
                        "Данные из профиля. Подключите SSH-доступ, чтобы видеть файл на сервере."
                    },
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                if (state.configPath.isNotBlank() && server.sshHost.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Для формы, удаления и правки файла нужен SSH-пароль. " +
                            "Файл на сервере можно посмотреть в редакторе.",
                        color = TextDim,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            if (state.custom.isEmpty() && pending.isEmpty()) {
                item {
                    Surface(
                        color = Surface1,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Пока не добавлено. Добавьте провайдера формой или вставьте готовый JSON " +
                                "из инструкции хостинга.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(state.custom, key = { "cfg-" + it.id }) { provider ->
                    RemoteProviderRow(
                        provider = provider,
                        onDelete = { deleteTarget = provider },
                        onEditKey = { configTarget = provider }
                    )
                }
            }

            if (pending.isNotEmpty()) {
                item {
                    Text(
                        "Не сохранено на сервере: ${pending.size}",
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                items(pending, key = { "pending-" + it.id }) { draft ->
                    PendingProviderRow(
                        draft = draft,
                        onRemove = { pending = pending.filterNot { it.id == draft.id } }
                    )
                }
                item {
                    PrimaryButton(
                        text = "Сохранить выбранные (${pending.size})",
                        onClick = {
                            val queue = pending
                            pending = emptyList()
                            queue.forEach { draft ->
                                vm.createProvider(server, draft.toCustom()) { result ->
                                    result
                                        .onSuccess { message -> toast(message) }
                                        .onFailure { toast("${draft.id}: ${it.message ?: "ошибка"}") }
                                }
                            }
                        }
                    )
                }
            }

            item {
                PrimaryButton(
                    text = "Добавить провайдера",
                    onClick = { showAdd = true }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = "Вставить JSON",
                        onClick = { showManual = true },
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        text = "Из файла",
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                SecondaryButton(
                    text = "Редактировать opencode.json",
                    onClick = { vm.openConfigDialog(server) }
                )
            }

            if (state.integrations.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionTitle("Интеграции сервера")
                    Text(
                        "Способы авторизации, которые поддерживает OpenCode: " +
                            state.integrations.joinToString { integration ->
                                val methods = integration.methods.joinToString { it.label }
                                val accounts = if (integration.connections.isEmpty()) "" else {
                                    ", аккаунтов: ${integration.connections.size}"
                                }
                                "${integration.name} ($methods$accounts)"
                            },
                        color = TextDim,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }

    // Все диалоги на экране — через один DialogHost: так системный Back
    // закрывает именно верхний диалог, а не предыдущий экран.
    DialogHost(
        hasOpenDialog = keyTarget != null || authTarget != null || connectionTarget != null ||
            vm.oauth != null || configTarget != null || showAdd || showManual ||
            configDialog != null || deleteTarget != null
    )

    if (keyTarget != null) {
        val provider = keyTarget!!
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                if (!keyBusy) {
                    keyTarget = null
                    keyError = ""
                }
            }
        ) {
            KeyDialogContent(
                server = server,
                providerId = provider.id,
                providerName = provider.name,
                busy = keyBusy,
                error = keyError,
                onSave = { key ->
                    keyBusy = true
                    keyError = ""
                    vm.connectKey(server, provider, key) { result ->
                        keyBusy = false
                        result
                            .onSuccess {
                                toast("Ключ сохранён")
                                keyTarget = null
                                keyError = ""
                            }
                            .onFailure { keyError = it.message ?: "Не удалось сохранить ключ" }
                    }
                },
                onCancel = {
                    keyTarget = null
                    keyError = ""
                }
            )
        }
    }

    authTarget?.let { provider ->
        val integration = state.integrations.firstOrNull {
            it.id == provider.integrationID || it.id == provider.id
        }
        if (integration != null) {
            AlertDialog(
                onDismissRequest = { authTarget = null },
                title = {
                    Text(
                        "Вход · ${provider.name}",
                        color = TextMain,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Интеграция ${integration.name} · ${integration.id}",
                            color = TextDim,
                            fontSize = 11.sp
                        )
                        if (integration.connections.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Подключённые аккаунты",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            integration.connections.forEach { connection ->
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            connection.label.ifBlank {
                                                if (connection.type == "env") connection.id else "Аккаунт"
                                            },
                                            color = TextMain,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            buildString {
                                                append(connection.method.ifBlank { connection.type })
                                                if (connection.id.isNotBlank()) append(" · ${connection.id}")
                                            },
                                            color = TextDim,
                                            fontSize = 10.sp
                                        )
                                    }
                                    if (connection.id.isNotBlank() && connection.type != "env") {
                                        IconButton(
                                            onClick = { connectionTarget = provider to connection }
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Удалить аккаунт",
                                                tint = Red,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        if (integration.methods.isEmpty()) {
                            Text(
                                "У сервера нет способов входа для этого провайдера. " +
                                    "Настройте ключ или переменную окружения на сервере.",
                                color = TextMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        } else {
                            Text(
                                "Выберите способ входа:",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            integration.methods.forEach { method ->
                                SecondaryButton(
                                    text = method.label,
                                    onClick = {
                                        when {
                                            method.isKey -> {
                                                keyTarget = provider
                                                authTarget = null
                                            }

                                            method.isOAuth -> {
                                                vm.startOAuth(server, integration, method)
                                                authTarget = null
                                            }

                                            else -> toast(
                                                "Этот способ выполняется на сервере: " +
                                                    "${integration.id} / ${method.type}"
                                            )
                                        }
                                    }
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(
                                "OpenCode Go: если сервер предлагает вход через браузер, " +
                                    "используйте его; иначе вставьте API-ключ из консоли OpenCode.",
                                color = TextDim,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { authTarget = null }) {
                        Text("Закрыть", color = TextMuted)
                    }
                },
                containerColor = Surface2
            )
        }
    }

    connectionTarget?.let { (provider, connection) ->
        ConfirmDialog(
            title = "Удалить аккаунт?",
            text = "«${connection.label.ifBlank { connection.id }}» будет отключён от ${provider.name}. " +
                "Ключ или токен удаляется на сервере.",
            onConfirm = {
                vm.removeConnection(server, connection.id) { result ->
                    result
                        .onSuccess { toast("Аккаунт отключён") }
                        .onFailure { toast(it.message ?: "Не удалось отключить аккаунт") }
                }
            },
            onDismiss = { connectionTarget = null }
        )
    }

    vm.oauth?.let { auth ->
        var code by remember(auth.attemptId) { mutableStateOf("") }
        var openedOnce by remember(auth.attemptId) { mutableStateOf(false) }
        fun openBrowser() {
            if (auth.url.isBlank()) return
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(auth.url)))
            } catch (e: Exception) {
                toast("Не удалось открыть браузер: ${e.message ?: "ошибка"}")
            }
        }

        LaunchedEffect(auth.attemptId, auth.url) {
            if (auth.url.isNotBlank() && !auth.starting && !openedOnce) {
                openedOnce = true
                openBrowser()
            }
        }
        LaunchedEffect(auth.attemptId, auth.status) {
            if (auth.status == "complete") toast("Вход выполнен")
        }

        AlertDialog(
            onDismissRequest = {
                if (!auth.completing) vm.cancelOAuth(server)
            },
            title = {
                Text(
                    "Вход · ${auth.integrationName}",
                    color = TextMain,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(auth.methodLabel, color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    when {
                        auth.starting -> LoadingLine("Начинаем вход…")
                        auth.status == "complete" -> Text(
                            "Готово: аккаунт подключён.",
                            color = Green,
                            fontSize = 13.sp
                        )

                        auth.status == "failed" || auth.status == "expired" -> Text(
                            auth.error.ifBlank { "Вход не завершён" },
                            color = Red,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        else -> Text(
                            "Ожидаем подтверждение в браузере…",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }

                    if (auth.url.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        CodeBlock(auth.url.take(600))
                    }
                    if (auth.instructions.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            auth.instructions,
                            color = TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                    if (auth.bridgeActive) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Callback-порт проброшен через SSH: браузер вернёт результат на сервер.",
                            color = Green,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    } else if (auth.bridgeError.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            auth.bridgeError,
                            color = Accent,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    if (auth.codeRequired) {
                        Spacer(Modifier.height(8.dp))
                        LabeledField(
                            label = "Код из браузера",
                            value = code,
                            onValueChange = { code = it },
                            placeholder = "Вставьте код",
                            enabled = !auth.completing,
                            hint = "Если браузер показал код или ссылку с code=…, вставьте сюда."
                        )
                    }
                    if (auth.error.isNotBlank() && auth.status !in setOf("failed", "expired")) {
                        Spacer(Modifier.height(6.dp))
                        Text(auth.error, color = Red, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            },
            confirmButton = {
                if (auth.codeRequired) {
                    TextButton(
                        enabled = !auth.completing && code.isNotBlank() && !auth.starting,
                        onClick = { vm.submitOAuthCode(server, code) }
                    ) {
                        if (auth.completing) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Accent
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Подтвердить", color = Accent)
                    }
                } else {
                    TextButton(
                        enabled = auth.url.isNotBlank() && !auth.starting,
                        onClick = { openBrowser() }
                    ) {
                        Icon(Icons.Filled.Link, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Открыть браузер", color = Accent)
                    }
                }
            },
            dismissButton = {
                Row {
                    if (auth.url.isNotBlank()) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("OAuth", auth.url))
                            toast("Ссылка скопирована")
                        }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                null,
                                tint = TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("Копировать", color = TextMuted)
                        }
                    }
                    TextButton(onClick = { vm.cancelOAuth(server) }, enabled = !auth.completing) {
                        Text(if (auth.finished) "Закрыть" else "Отмена", color = TextMuted)
                    }
                }
            },
            containerColor = Surface2
        )
    }

    configTarget?.let { provider ->
        var value by remember(provider.id) { mutableStateOf("") }
        var busy by remember(provider.id) { mutableStateOf(false) }
        var error by remember(provider.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) configTarget = null },
            title = { Text("Ключ · ${provider.name}", color = TextMain, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "Новый ключ заменит ${if (provider.hasKey) "текущий" else "пустой"} в opencode.json.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LabeledField(
                        label = "API-ключ",
                        value = value,
                        onValueChange = { value = it },
                        secret = true,
                        placeholder = "sk-…",
                        enabled = !busy
                    )
                    if (error.isNotBlank()) Text(error, color = Red, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && value.isNotBlank(),
                    onClick = {
                        busy = true
                        error = ""
                        vm.setProviderKey(server, provider.id, value.trim()) { result ->
                            busy = false
                            result
                                .onSuccess {
                                    toast(it)
                                    configTarget = null
                                }
                                .onFailure { error = it.message ?: "Не удалось сохранить" }
                        }
                    }
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Сохранить", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { configTarget = null }, enabled = !busy) {
                    Text("Отмена", color = TextMuted)
                }
            },
            containerColor = Surface2
        )
    }

    if (showAdd) {
        AddProviderDialog(
            server = server,
            onDismiss = { showAdd = false },
            onAdd = { provider, done ->
                vm.createProvider(server, provider) { result ->
                    result
                        .onSuccess { message ->
                            toast(message)
                            showAdd = false
                        }
                        .onFailure { done(it.message ?: "Не удалось добавить провайдера") }
                }
            }
        )
    }

    if (showManual) {
        ManualProviderDialog(
            server = server,
            onDismiss = { showManual = false },
            onSave = { json, done ->
                vm.importProviderJson(server, json) { result ->
                    result
                        .onSuccess { message ->
                            toast(message)
                            showManual = false
                        }
                        .onFailure { done(it.message ?: "Не удалось сохранить JSON") }
                }
            }
        )
    }

    configDialog?.let { dialog ->
        var text by remember(dialog.path, dialog.content) { mutableStateOf(dialog.content) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) vm.closeConfigDialog() },
            title = { Text("opencode.json", color = TextMain, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(dialog.path, color = TextDim, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp),
                        enabled = dialog.editable && !busy,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextMain
                        ),
                        placeholder = {
                            Text(
                                if (dialog.editable) "{\n  \"providers\": {}\n}" else "SSH-доступ не настроен",
                                color = TextDim,
                                fontSize = 11.sp
                            )
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border,
                            focusedContainerColor = Surface1,
                            unfocusedContainerColor = Surface1,
                            cursorColor = Accent
                        )
                    )
                    if (error.isNotBlank()) Text(error, color = Red, fontSize = 12.sp)
                    Text(
                        "Как в настольной версии: можно поправить файл вручную. " +
                            "Приложение проверит, что это корректный JSON.",
                        color = TextDim,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = dialog.editable && !busy,
                    onClick = {
                        busy = true
                        error = ""
                        vm.saveConfig(server, text) { result ->
                            busy = false
                            result
                                .onSuccess { toast(it) }
                                .onFailure { error = it.message ?: "Не удалось сохранить" }
                        }
                    }
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Сохранить", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.closeConfigDialog() }, enabled = !busy) {
                    Text("Закрыть", color = TextMuted)
                }
            },
            containerColor = Surface2
        )
    }

    deleteTarget?.let { provider ->
        ConfirmDialog(
            title = "Удалить провайдера?",
            text = "«${provider.name}» будет убран из opencode.json на сервере и из профиля.",
            onConfirm = {
                vm.removeCustomProvider(server, provider) { result ->
                    result
                        .onSuccess { toast("Провайдер удалён") }
                        .onFailure { toast(it.message ?: "Не удалось удалить") }
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** Пустой якорь для BackHandler: снимает флаг «есть открытый диалог». */
@Composable
private fun DialogHost(hasOpenDialog: Boolean) {
    androidx.activity.compose.BackHandler(enabled = hasOpenDialog) { }
}

@Composable
private fun KeyDialogContent(
    server: ServerProfile,
    providerId: String,
    providerName: String,
    busy: Boolean,
    error: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var value by remember(providerId) { mutableStateOf("") }
    var localError by remember(providerId) { mutableStateOf("") }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Surface2,
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Ключ · $providerName", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            LabeledField(
                label = "API-ключ",
                value = value,
                onValueChange = { value = it },
                placeholder = "sk-…",
                secret = true,
                enabled = !busy
            )
            val shown = localError.ifBlank { error }
            if (shown.isNotBlank()) {
                Text(shown, color = Red, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "Ключ уйдёт на сервер ${server.name} и будет использоваться OpenCode для запросов.",
                color = TextDim,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = if (busy) "Сохраняем…" else "Сохранить",
                loading = busy,
                onClick = {
                    if (value.isBlank()) {
                        localError = "Введите ключ"
                    } else {
                        localError = ""
                        onSave(value.trim())
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Отмена", color = TextMuted)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun ProviderRow(
    provider: ProviderInfo,
    integration: IntegrationInfo?,
    canConnect: Boolean,
    onClick: () -> Unit
) {
    val connected = integration?.isConnected == true
    val action = when {
        connected -> "подключено"
        integration?.supportsOAuth == true -> "Войти"
        integration?.supportsKey == true -> "Ключ"
        canConnect -> "Настроить"
        else -> "без входа"
    }
    val accent = when {
        connected -> Green
        canConnect -> Accent
        else -> TextDim
    }
    Surface(
        onClick = onClick,
        enabled = canConnect,
        shape = RoundedCornerShape(14.dp),
        color = Surface1,
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (connected) Icons.Filled.Link else Icons.Filled.Key,
                null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    provider.name,
                    color = TextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(provider.id)
                        if (provider.activation.isNotBlank()) append(" · ${provider.activation}")
                        val methods = integration?.methods?.map { it.label }.orEmpty()
                        if (methods.isNotEmpty()) append(" · ${methods.joinToString()}")
                    },
                    color = TextDim,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(action, color = accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RemoteProviderRow(
    provider: RemoteProvider,
    onDelete: () -> Unit,
    onEditKey: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Surface1,
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        provider.name,
                        color = TextMain,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (provider.legacy) {
                        Spacer(Modifier.width(6.dp))
                        Text("V1", color = Accent, fontSize = 10.sp)
                    }
                }
                Text(
                    provider.baseUrl.ifBlank { "без baseURL" },
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(provider.id)
                        append(if (provider.hasKey) " · ключ задан" else " · без ключа")
                        if (provider.models.isNotEmpty()) append(" · моделей: ${provider.models.size}")
                    },
                    color = TextDim,
                    fontSize = 10.sp
                )
            }
            if (provider.manageable) {
                IconButton(onClick = onEditKey) {
                    Icon(Icons.Filled.Edit, contentDescription = "Изменить ключ", tint = TextMuted)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = Red)
                }
            }
        }
    }
}

@Composable
private fun PendingProviderRow(draft: PendingProvider, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1F1A10),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    draft.name,
                    color = TextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    draft.baseUrl.ifBlank { "без baseURL" },
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(draft.id)
                        append(if (draft.apiKey.isNotBlank()) " · ключ есть" else " · без ключа")
                        if (draft.models.isNotEmpty()) append(" · моделей: ${draft.models.size}")
                        if (draft.legacy) append(" · из V1")
                    },
                    color = TextDim,
                    fontSize = 10.sp
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Убрать", tint = TextMuted)
            }
        }
    }
}

@Composable
private fun AddProviderDialog(
    server: ServerProfile,
    onDismiss: () -> Unit,
    onAdd: (CustomProvider, (String) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf(ProviderConfig.DEFAULT_PACKAGE) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Новый провайдер", color = TextMain, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                LabeledField(
                    label = "Название",
                    value = name,
                    onValueChange = {
                        val previous = name
                        name = it
                        val auto = slug(previous)
                        val next = slug(it)
                        if (next.isNotBlank() && (id.isBlank() || id == auto)) id = next
                    },
                    placeholder = "OpenRouter",
                    enabled = !busy
                )
                LabeledField(
                    label = "ID провайдера",
                    value = id,
                    onValueChange = { id = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } },
                    placeholder = "openrouter",
                    enabled = !busy,
                    hint = "Латиница, цифры, - и _. Используется в моделях вида id/модель."
                )
                LabeledField(
                    label = "Base URL",
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = "https://openrouter.ai/api/v1",
                    enabled = !busy,
                    keyboardType = KeyboardType.Uri
                )
                LabeledField(
                    label = "API-ключ",
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "необязательно для локальных моделей",
                    secret = true,
                    enabled = !busy
                )
                LabeledField(
                    label = "Модели",
                    value = models,
                    onValueChange = { models = it },
                    placeholder = "gpt-6.1-sol, llama3.1",
                    enabled = !busy,
                    singleLine = false,
                    hint = "Через запятую или с новой строки. Можно оставить пустым."
                )
                LabeledField(
                    label = "Пакет OpenCode",
                    value = packageName,
                    onValueChange = { packageName = it },
                    enabled = !busy,
                    hint = "По умолчанию OpenAI-совместимый пакет. «@ai-sdk/…» тоже принимается."
                )
                Text(
                    "После сохранения приложение проверит endpoint по /models.",
                    color = TextDim,
                    fontSize = 11.sp
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = Red, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = ""
                    val providerId = id.trim()
                    if (providerId.isBlank()) {
                        error = "Укажите ID провайдера"
                        return@TextButton
                    }
                    if (server.sshHost.isBlank() || server.sshPassword.isBlank()) {
                        error = "В профиле сервера нет SSH-доступа"
                        return@TextButton
                    }
                    if (baseUrl.isBlank()) {
                        error = "Укажите Base URL провайдера"
                        return@TextButton
                    }
                    busy = true
                    val provider = CustomProvider(
                        id = providerId,
                        name = name.trim().ifBlank { providerId },
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        packageName = packageName.trim().ifBlank { ProviderConfig.DEFAULT_PACKAGE },
                        models = models
                            .split(',', '\n')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    )
                    onAdd(provider) { message ->
                        busy = false
                        error = message
                    }
                },
                enabled = !busy
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Добавить", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена", color = TextMuted) }
        },
        containerColor = Surface2
    )
}

@Composable
private fun ManualProviderDialog(
    server: ServerProfile,
    onDismiss: () -> Unit,
    onSave: (String, (String) -> Unit) -> Unit
) {
    var text by remember { mutableStateOf(MANUAL_EXAMPLE) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    val existingIds = remember(server.id) { server.customProviders.map { it.id } }

    fun validate(): String? = try {
        val root = org.json.JSONObject(text)
        val providers = root.optJSONObject("providers") ?: root.optJSONObject("provider")
        if (providers == null || providers.length() == 0) "Нет объекта providers" else null
    } catch (e: Exception) {
        "Некорректный JSON: ${e.message}"
    }

    fun submit() {
        val problem = validate()
        if (problem != null) {
            error = problem
            return
        }
        busy = true
        error = ""
        onSave(text) { message ->
            busy = false
            error = message
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Провайдер из JSON", color = TextMain, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "Вставьте конфигурацию провайдера — как в инструкции хостинга. " +
                        "Подходят форматы V2 (providers) и V1 (provider, npm, options): " +
                        "приложение приведёт их к V2.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    enabled = !busy,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMain
                    ),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedContainerColor = Surface1,
                        unfocusedContainerColor = Surface1,
                        cursorColor = Accent
                    )
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = Red, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (existingIds.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = confirmOverwrite,
                            onCheckedChange = { confirmOverwrite = it },
                            enabled = !busy
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Разрешить перезапись (${existingIds.joinToString()})",
                            color = TextDim,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (existingIds.isNotEmpty() && !confirmOverwrite) {
                        val problem = validate()
                        if (problem != null) {
                            error = problem
                            return@TextButton
                        }
                        error = "Включите перезапись существующих провайдеров"
                        return@TextButton
                    }
                    submit()
                }
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Сохранить", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена", color = TextMuted) }
        },
        containerColor = Surface2
    )
}

private fun slug(value: String): String = value
    .lowercase()
    .map { if (it.isLetterOrDigit()) it else '-' }
    .joinToString("")
    .trim('-')
