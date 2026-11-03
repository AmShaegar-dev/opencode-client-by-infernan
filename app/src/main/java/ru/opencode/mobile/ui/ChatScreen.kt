package ru.opencode.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.Attachments
import ru.opencode.mobile.data.AgentInfo
import ru.opencode.mobile.data.ChatMessage
import ru.opencode.mobile.data.ModelInfo
import ru.opencode.mobile.data.PromptAttachment
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.data.ToolCall
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.AccentSoft
import ru.opencode.mobile.ui.theme.Bg
import ru.opencode.mobile.ui.theme.Blue
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Green
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.Surface2
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

@Composable
fun ChatScreen(vm: AppViewModel, server: ServerProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val chat = vm.chat
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showThinking by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDiff by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var showAgents by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var input by remember(chat.sessionId) { mutableStateOf("") }
    var attachments by remember(chat.sessionId) { mutableStateOf(listOf<PromptAttachment>()) }
    var attaching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun uploadAndInsert(uri: Uri) {
        vm.uploadToServer(server, uri) { result ->
            result
                .onSuccess { path ->
                    val note = if (input.isBlank()) {
                        "Файл загружен на сервер: $path"
                    } else {
                        "$input\n\nФайл загружен на сервер: $path"
                    }
                    input = note
                    toast("Файл загружен: $path")
                }
                .onFailure { toast(it.message ?: "Не удалось загрузить файл") }
        }
    }

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            attaching = true
            scope.launch {
                val size = withContext(Dispatchers.IO) { Attachments.sizeOf(context, uri) }
                if (size != null && size > Attachments.MAX_BYTES) {
                    attaching = false
                    toast("Файл больше 20 МБ — загружаю на сервер…")
                    uploadAndInsert(uri)
                    return@launch
                }
                val attachment = withContext(Dispatchers.IO) { Attachments.prepare(context, uri) }
                attaching = false
                when {
                    attachment == null -> {
                        toast("Не удалось приложить к сообщению — загружаю файл на сервер…")
                        uploadAndInsert(uri)
                    }
                    attachments.size >= 5 -> toast("Можно прикрепить до 5 файлов")
                    else -> attachments = attachments + attachment
                }
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) uploadAndInsert(uri)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportChat(server, chat.sessionId, uri) { result ->
                result.onSuccess(::toast).onFailure { toast(it.message ?: "Ошибка экспорта") }
            }
        }
    }

    LaunchedEffect(chat.messages.size, chat.sending) {
        if (chat.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(chat.messages.lastIndex) }
        }
    }

    val selected = vm.selectedModel
    val selectedInfo = vm.models.firstOrNull {
        it.ref.providerID == selected?.providerID && it.ref.id == selected?.id
    }

    Column(modifier.fillMaxSize()) {
        AppHeader(
            title = chat.title.ifBlank { "Чат" },
            subtitle = selectedInfo?.let {
                buildString {
                    append(it.title).append(" · ").append(it.ref.providerID)
                    selected?.variant?.takeIf { variant -> variant.isNotBlank() }?.let { variant ->
                        append(" · ").append(variantLabel(variant))
                    }
                }
            } ?: selected?.key ?: "Модель не выбрана",
            onBack = { vm.backFromChat(server.id) },
            actions = {
                if (selectedInfo?.variants?.isNotEmpty() == true) {
                    IconButton(onClick = { showThinking = true }) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = "Уровень размышлений",
                            tint = TextMuted
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Меню", tint = TextMuted)
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        containerColor = Surface2
                    ) {
                        DropdownMenuItem(
                            text = { Text("Переименовать", color = TextMain) },
                            leadingIcon = { Icon(Icons.Filled.Edit, null, tint = TextMuted) },
                            onClick = {
                                menu = false
                                renameText = chat.title
                                showRename = true
                            }
                        )
                        val lastUser = chat.messages.lastOrNull { it.isUser && it.text.isNotBlank() }
                        if (lastUser != null) {
                            DropdownMenuItem(
                                text = { Text("Повторить последний запрос", color = TextMain) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null, tint = TextMuted) },
                                onClick = {
                                    menu = false
                                    vm.send(server, lastUser.text)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Найти в чате", color = TextMain) },
                            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
                            onClick = {
                                menu = false
                                searchQuery = ""
                                showSearch = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Команды", color = TextMain) },
                            leadingIcon = { Icon(Icons.Filled.Build, null, tint = TextMuted) },
                            onClick = {
                                menu = false
                                vm.loadCommandsAndAgents(server)
                                showCommands = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Агенты", color = TextMain) },
                            leadingIcon = { Icon(Icons.Filled.Memory, null, tint = TextMuted) },
                            onClick = {
                                menu = false
                                vm.loadCommandsAndAgents(server)
                                showAgents = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Изменения файлов", color = TextMain) },
                            leadingIcon = { Icon(Icons.Filled.Download, null, tint = TextMuted) },
                            onClick = {
                                menu = false
                                showDiff = true
                                vm.loadDiffs(server)
                            }
                        )
                        if (selectedInfo != null && selectedInfo.variants.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Уровень размышлений", color = TextMain) },
                                leadingIcon = { Icon(Icons.Filled.Tune, null, tint = TextMuted) },
                                onClick = { menu = false; showThinking = true }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Экспорт чата", color = TextMain) },
                            leadingIcon = { Icon(Icons.Filled.Download, null, tint = TextMuted) },
                            onClick = {
                                menu = false
                                exportLauncher.launch(vm.suggestedFileName("opencode-chat"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Загрузить файл на сервер", color = TextMain) },
                            leadingIcon = { Icon(Icons.Filled.UploadFile, null, tint = TextMuted) },
                            onClick = {
                                menu = false
                                uploadLauncher.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Провайдеры и ключи", color = TextMain) },
                            onClick = { menu = false; vm.openProviders(server.id) }
                        )
                        DropdownMenuItem(
                            text = { Text("Настройки сервера", color = TextMain) },
                            onClick = { menu = false; vm.openEditor(server.id) }
                        )
                    }
                }
            }
        )

        if (chat.sending) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Accent,
                trackColor = Surface1
            )
        }

        if (chat.error.isNotBlank()) {
            Surface(
                color = Color(0xFF2A1414),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(chat.error, color = Red, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.loadChat(server, chat.sessionId) }) {
                        Text("Обновить", color = Accent, fontSize = 12.sp)
                    }
                }
            }
        }

        if (chat.loading) {
            LoadingLine("Загружаем сообщения…")
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (chat.messages.isEmpty() && !chat.loading) {
                item {
                    EmptyState(
                        title = "Чат пуст",
                        text = "Напишите задачу: OpenCode прочитает файлы проекта, выполнит команды и " +
                            "вернёт результат. Модель можно сменить кнопкой ниже."
                    )
                }
            }
            items(chat.messages, key = { it.id }) { message ->
                MessageItem(message, isLast = message.id == chat.messages.lastOrNull()?.id, sending = chat.sending)
            }
            if (chat.sending) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.5.dp,
                            color = Accent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Модель думает…", color = TextMuted, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.stop(server) }) {
                            Text("Остановить", color = Red, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Surface(color = Bg, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding().imePadding()) {
                Surface(
                    onClick = {
                        showModels = true
                        if (vm.models.isEmpty()) vm.reloadModels(server)
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = Surface1,
                    border = BorderStroke(1.dp, Border)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Memory, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            buildString {
                                append(selectedInfo?.title ?: selected?.key ?: "Выбрать модель")
                                selected?.variant?.takeIf { it.isNotBlank() }?.let {
                                    append(" · ").append(variantLabel(it))
                                }
                            },
                            color = TextMain,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (selectedInfo?.free == true) {
                            Spacer(Modifier.width(6.dp))
                            Text("free", color = Green, fontSize = 10.sp)
                        }
                        Icon(Icons.Filled.ArrowDropDown, null, tint = TextMuted)
                    }
                }
                if (chat.uploading) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Загружаем файл на сервер…", color = TextMuted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (attachments.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        attachments.forEach { attachment ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Surface1,
                                border = BorderStroke(1.dp, Border)
                            ) {
                                Row(
                                    Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (Attachments.isImage(attachment.name)) Icons.Filled.Image
                                        else Icons.Filled.AttachFile,
                                        null,
                                        tint = Accent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            attachment.name,
                                            color = TextMain,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 150.dp)
                                        )
                                        Text(
                                            Attachments.humanSize(attachment.sizeBytes),
                                            color = TextDim,
                                            fontSize = 9.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            attachments = attachments.filterNot { it == attachment }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, "Убрать", tint = TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    androidx.compose.material3.FilledIconButton(
                        onClick = { attachLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.size(50.dp),
                        enabled = !chat.sending && !attaching,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Surface1,
                            contentColor = if (attaching) TextDim else TextMain
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (attaching) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                        } else {
                            Icon(Icons.Filled.AttachFile, contentDescription = "Прикрепить файл")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Спросите OpenCode…", color = TextDim, fontSize = 14.sp) },
                        maxLines = 6,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain,
                            focusedBorderColor = Border,
                            unfocusedBorderColor = Border,
                            focusedContainerColor = Surface1,
                            unfocusedContainerColor = Surface1,
                            cursorColor = Accent
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (chat.sending) {
                                vm.stop(server)
                            } else if (input.isNotBlank() || attachments.isNotEmpty()) {
                                val text = input
                                val files = attachments
                                input = ""
                                attachments = emptyList()
                                vm.send(server, text, files)
                            }
                        },
                        modifier = Modifier.size(50.dp),
                        enabled = !attaching,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (chat.sending) Color(0xFF3A1414) else Accent,
                            contentColor = if (chat.sending) Red else Color(0xFF1A1205)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            if (chat.sending) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (chat.sending) "Остановить" else "Отправить"
                        )
                    }
                }
            }
        }
    }

    if (showModels) {
        ModelPickerDialog(
            vm = vm,
            server = server,
            selected = selected,
            onDismiss = { showModels = false },
            onPick = { model, variant ->
                vm.selectModel(server, model.ref.copy(variant = variant))
                showModels = false
            }
        )
    }

    if (showThinking && selectedInfo != null) {
        VariantDialog(
            model = selectedInfo,
            selectedVariant = selected?.variant,
            onDismiss = { showThinking = false },
            onPick = { variant ->
                vm.selectModel(server, selectedInfo.ref.copy(variant = variant))
                showThinking = false
            }
        )
    }

    if (showCommands) {
        AlertDialog(
            onDismissRequest = { showCommands = false },
            title = { Text("Команды", color = TextMain, fontWeight = FontWeight.SemiBold) },
            text = {
                when {
                    vm.commandsLoading -> LoadingLine("Загружаем команды…")
                    vm.commands.isEmpty() -> Text(
                        "Сервер не вернул команд.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(vm.commands, key = { it.name }) { command ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        input = "/${command.name} "
                                        showCommands = false
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text("/${command.name}", color = Accent, fontSize = 13.sp)
                                if (command.description.isNotBlank()) {
                                    Text(
                                        command.description,
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCommands = false }) { Text("Закрыть", color = TextMuted) }
            },
            containerColor = Surface2
        )
    }

    if (showAgents) {
        AlertDialog(
            onDismissRequest = { showAgents = false },
            title = { Text("Агенты", color = TextMain, fontWeight = FontWeight.SemiBold) },
            text = {
                when {
                    vm.commandsLoading -> LoadingLine("Загружаем агентов…")
                    vm.agents.isEmpty() -> Text(
                        "Сервер не вернул агентов.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(vm.agents, key = { it.id }) { agent ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.switchAgent(server, agent) { result ->
                                            result
                                                .onSuccess { toast("Агент: ${agentLabel(agent)}") }
                                                .onFailure { toast(it.message ?: "Не удалось сменить агента") }
                                        }
                                        showAgents = false
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(agentLabel(agent), color = TextMain, fontSize = 13.sp)
                                Text(
                                    buildString {
                                        agentModeLabel(agent.mode)?.let { append(it) }
                                        agentDescription(agent)?.let {
                                            if (isNotEmpty()) append(" · ")
                                            append(it)
                                        }
                                    },
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAgents = false }) { Text("Закрыть", color = TextMuted) }
            },
            containerColor = Surface2
        )
    }

    if (showDiff) {
        AlertDialog(
            onDismissRequest = { showDiff = false; vm.clearDiffs() },
            title = { Text("Изменения файлов", color = TextMain, fontWeight = FontWeight.SemiBold) },
            text = {
                when {
                    vm.diffs.loading -> LoadingLine("Получаем изменения…")
                    vm.diffs.error.isNotBlank() -> Text(
                        vm.diffs.error,
                        color = Red,
                        fontSize = 12.sp
                    )
                    vm.diffs.items.isEmpty() -> Text(
                        "В последнем ответе модель не меняла файлы.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        items(vm.diffs.items, key = { it.file }) { diff ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        diff.file,
                                        color = TextMain,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text("+${diff.additions}", color = Green, fontSize = 10.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text("-${diff.deletions}", color = Red, fontSize = 10.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                CodeBlock(diff.patch.take(12_000))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiff = false; vm.clearDiffs() }) {
                    Text("Закрыть", color = TextMuted)
                }
            },
            containerColor = Surface2
        )
    }

    if (showSearch) {
        val matches = if (searchQuery.isBlank()) {
            emptyList()
        } else {
            chat.messages.filter { message ->
                !message.isHidden && (
                    message.text.contains(searchQuery, ignoreCase = true) ||
                        message.reasoning.contains(searchQuery, ignoreCase = true)
                    )
            }
        }
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("Поиск в чате", color = TextMain, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Текст сообщения", color = TextDim, fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextDim) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain,
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border,
                            focusedContainerColor = Surface1,
                            unfocusedContainerColor = Surface1
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    if (searchQuery.isNotBlank() && matches.isEmpty()) {
                        Text("Ничего не найдено", color = TextMuted, fontSize = 12.sp)
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(matches, key = { it.id }) { message ->
                            val index = chat.messages.indexOf(message)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSearch = false
                                        if (index >= 0) {
                                            scope.launch { listState.animateScrollToItem(index) }
                                        }
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    if (message.isUser) "Вы" else "OpenCode",
                                    color = TextDim,
                                    fontSize = 10.sp
                                )
                                Text(
                                    message.text.ifBlank { message.reasoning },
                                    color = TextMain,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearch = false }) {
                    Text("Закрыть", color = TextMuted)
                }
            },
            containerColor = Surface2
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Название чата", color = TextMain, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = renameText.trim()
                    if (title.isNotBlank()) vm.renameChat(server, chat.sessionId, title)
                    showRename = false
                }) { Text("Сохранить", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Отмена", color = TextMuted) }
            },
            containerColor = Surface2
        )
    }
}

@Composable
private fun MessageItem(message: ChatMessage, isLast: Boolean, sending: Boolean) {
    if (message.isHidden) return
    val context = LocalContext.current
    fun copyMessage() {
        val text = message.text.ifBlank { message.reasoning }
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("OpenCode", text))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    if (message.isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = AccentSoft,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.fillMaxWidth(0.88f)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val body = message.displayText
                    val files = message.files
                    if (body.isNotEmpty()) {
                        SelectionContainer {
                            Text(
                                body,
                                color = TextMain,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                    if (files.isNotEmpty()) {
                        if (body.isNotEmpty()) Spacer(Modifier.height(6.dp))
                        files.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (Attachments.isImage(name)) Icons.Filled.Image else Icons.Filled.AttachFile,
                                    null,
                                    tint = Accent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(name, color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    } else if (body.isEmpty()) {
                        Text("📎 вложение", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        return
    }

    var reasoningOpen by remember(message.id) { mutableStateOf(false) }
    var toolsOpen by remember(message.id) { mutableStateOf(false) }

    Surface(
        color = Surface1,
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OpenCode", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (message.modelLabel.isNotBlank()) {
                    Text(" · ${message.modelLabel}", color = TextDim, fontSize = 10.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { copyMessage() }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Копировать ответ",
                        tint = TextDim,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (!message.completed) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Accent
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("обрабатывает…", color = TextDim, fontSize = 10.sp)
                }
            }

            if (message.reasoning.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (reasoningOpen) "Скрыть размышления" else "Размышления (${message.reasoning.length})",
                    color = Blue,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { reasoningOpen = !reasoningOpen }
                )
                if (reasoningOpen) {
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            message.reasoning,
                            color = TextDim,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            if (message.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                SelectionContainer { RichText(message.text) }
            }

            if (message.tools.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { toolsOpen = !toolsOpen }
                ) {
                    Icon(Icons.Filled.Build, null, tint = TextMuted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Инструменты: ${message.tools.size}${if (toolsOpen) " ▴" else " ▾"}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                if (toolsOpen) {
                    message.tools.forEach { ToolCallCard(it) }
                }
            }

            if (message.error.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Ошибка: ${message.error.take(800)}", color = Red, fontSize = 12.sp)
            }

            if (isLast && sending && !message.completed) {
                Text("▍", color = Accent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ToolCallCard(tool: ToolCall) {
    var open by remember(tool.name, tool.status, tool.output.length) { mutableStateOf(false) }
    Surface(
        color = Surface2,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { open = !open }
            ) {
                Text(tool.name, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    when (tool.status) {
                        "completed" -> "готово"
                        "running" -> "выполняется"
                        "streaming" -> "вызов"
                        "error" -> "ошибка"
                        else -> tool.status
                    },
                    color = when (tool.status) {
                        "completed" -> Green
                        "error" -> Red
                        else -> TextMuted
                    },
                    fontSize = 10.sp
                )
                if (tool.input.isNotBlank() || tool.output.isNotBlank()) {
                    Text(if (open) "  ▴" else "  ▾", color = TextDim, fontSize = 10.sp)
                }
            }
            if (open) {
                if (tool.input.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    CodeBlock(tool.input.take(1500))
                }
                if (tool.output.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    CodeBlock(tool.output.take(3000))
                }
            }
        }
    }
}

/** Простейший рендер: текст + блоки ```…``` моноширинным. */
@Composable
fun RichText(text: String) {
    val segments = text.split("```")
    if (segments.size == 1) {
        Text(text, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
        return
    }
    Column {
        segments.forEachIndexed { index, segment ->
            if (index % 2 == 1) {
                val body = segment.substringAfter('\n', segment)
                CodeBlock(body.trimEnd())
            } else if (segment.isNotBlank()) {
                Text(segment.trim(), color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (index != segments.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

private enum class ModelFilter { ALL, ENABLED, FAVORITES }

/**
 * Полноэкранный выбор моделей. При десятках моделей обычный AlertDialog
 * превращался в узкий длинный список, поэтому здесь есть поиск, фильтр,
 * группировка по провайдеру и отдельная зона переключателей.
 */
@Composable
private fun ModelPickerDialog(
    vm: AppViewModel,
    server: ServerProfile,
    selected: ru.opencode.mobile.data.ModelRef?,
    onDismiss: () -> Unit,
    onPick: (ModelInfo, String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ModelFilter.ALL) }
    var variantTarget by remember { mutableStateOf<ModelInfo?>(null) }

    val enabledCount = vm.models.count { it.enabled }
    val favoriteCount = vm.models.count { server.favoriteModels.contains(it.ref.key) }
    val filtered = remember(vm.models, query, filter, server.favoriteModels) {
        vm.models.filter { model ->
            val matchesQuery = query.isBlank() ||
                model.title.contains(query, ignoreCase = true) ||
                model.ref.providerID.contains(query, ignoreCase = true) ||
                model.ref.id.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                ModelFilter.ALL -> true
                ModelFilter.ENABLED -> model.enabled
                ModelFilter.FAVORITES -> server.favoriteModels.contains(model.ref.key)
            }
            matchesQuery && matchesFilter
        }
    }
    val groups = remember(filtered) {
        filtered.groupBy { it.ref.providerID }
            .toList()
            .sortedBy { it.first.lowercase() }
    }
    val selectedTitle = vm.models.firstOrNull { it.ref.key == selected?.key }?.title
        ?: selected?.key

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Модели",
                            color = TextMain,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Включено $enabledCount из ${vm.models.size} · изменения сохраняются на сервере",
                            color = TextDim,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = TextMuted)
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Найти модель или провайдера", color = TextDim, fontSize = 13.sp) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextDim) },
                    trailingIcon = if (query.isBlank()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Очистить",
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedContainerColor = Surface1,
                        unfocusedContainerColor = Surface1
                    )
                )

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == ModelFilter.ALL,
                        onClick = { filter = ModelFilter.ALL },
                        label = { Text("Все · ${vm.models.size}") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Surface1,
                            labelColor = TextMuted,
                            selectedContainerColor = AccentSoft,
                            selectedLabelColor = Accent
                        )
                    )
                    FilterChip(
                        selected = filter == ModelFilter.ENABLED,
                        onClick = { filter = ModelFilter.ENABLED },
                        label = { Text("Включённые · $enabledCount") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Surface1,
                            labelColor = TextMuted,
                            selectedContainerColor = AccentSoft,
                            selectedLabelColor = Accent
                        )
                    )
                    FilterChip(
                        selected = filter == ModelFilter.FAVORITES,
                        onClick = { filter = ModelFilter.FAVORITES },
                        label = { Text("Избранные · $favoriteCount") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Surface1,
                            labelColor = TextMuted,
                            selectedContainerColor = AccentSoft,
                            selectedLabelColor = Accent
                        )
                    )
                }

                if (vm.modelsUpdating.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Accent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Сохраняем изменения: ${vm.modelsUpdating.size}…",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                if (vm.modelsError.isNotBlank()) {
                    val sshError = vm.modelsError.contains("SSH", ignoreCase = true) ||
                        vm.modelsError.contains("Порт 22", ignoreCase = true)
                    Surface(
                        color = Color(0xFF2A1414),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                vm.modelsError,
                                color = Red,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                if (sshError) {
                                    onDismiss()
                                    vm.openEditor(server.id)
                                } else {
                                    vm.reloadModels(server)
                                }
                            }) {
                                Text(if (sshError) "Настройки" else "Повторить", color = Accent, fontSize = 12.sp)
                            }
                        }
                    }
                }

                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    when {
                        vm.modelsLoading && vm.models.isEmpty() -> item {
                            LoadingLine("Загружаем модели…")
                        }

                        vm.models.isEmpty() -> item {
                            EmptyState(
                                title = "Модели не найдены",
                                text = "Подключите провайдера или добавьте ключ — после этого модели появятся здесь."
                            )
                            TextButton(
                                onClick = { onDismiss(); vm.openProviders(server.id) },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Text("Открыть провайдеры", color = Accent)
                            }
                        }

                        filtered.isEmpty() -> item {
                            Text(
                                if (filter == ModelFilter.ENABLED) {
                                    "Нет включённых моделей по этому запросу."
                                } else {
                                    "Ничего не найдено. Измените запрос."
                                },
                                color = TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                            )
                        }

                        else -> groups.forEach { (provider, providerModels) ->
                            item(key = "provider_$provider") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        provider.uppercase(),
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${providerModels.count { it.enabled }} / ${providerModels.size}",
                                        color = TextDim,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            items(providerModels, key = { it.ref.key }) { model ->
                                ModelRow(
                                    vm = vm,
                                    server = server,
                                    model = model,
                                    active = selected?.providerID == model.ref.providerID &&
                                        selected.id == model.ref.id,
                                    selectedVariant = selected
                                        ?.takeIf { selected.providerID == model.ref.providerID && selected.id == model.ref.id }
                                        ?.variant,
                                    favorite = server.favoriteModels.contains(model.ref.key),
                                    onFavorite = { vm.toggleFavorite(server, model.ref.key) },
                                    onPick = { picked ->
                                        if (picked.variants.isNotEmpty()) {
                                            variantTarget = picked
                                        } else {
                                            onPick(picked, null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Текущая модель", color = TextDim, fontSize = 10.sp)
                        Text(
                            selectedTitle ?: "не выбрана",
                            color = if (selected != null) TextMain else TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Готово", color = Accent)
                    }
                }
            }
        }
    }

    variantTarget?.let { model ->
        VariantDialog(
            model = model,
            selectedVariant = selected
                ?.takeIf { selected.providerID == model.ref.providerID && selected.id == model.ref.id }
                ?.variant,
            onDismiss = { variantTarget = null },
            onPick = { variant ->
                variantTarget = null
                onPick(model, variant)
            }
        )
    }
}

@Composable
private fun VariantDialog(
    model: ModelInfo,
    selectedVariant: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Сколько думать?", color = TextMain, fontWeight = FontWeight.SemiBold)
                Text(
                    model.title,
                    color = TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                VariantRow(
                    title = "Обычный режим",
                    subtitle = "Без дополнительного уровня размышлений",
                    selected = selectedVariant.isNullOrBlank(),
                    onClick = { onPick(null) }
                )
                model.variants.forEach { variant ->
                    VariantRow(
                        title = variantLabel(variant),
                        subtitle = variant,
                        selected = selectedVariant == variant,
                        onClick = { onPick(variant) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextMuted) }
        },
        containerColor = Surface2
    )
}

@Composable
private fun VariantRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) Accent else TextMain,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(subtitle, color = TextDim, fontSize = 10.sp)
        }
        if (selected) Text("✓", color = Accent, fontSize = 16.sp)
    }
}

private fun agentLabel(agent: AgentInfo): String = when (agent.id.lowercase()) {
    "build" -> "Сборка"
    "plan" -> "Планирование"
    "general" -> "Общий"
    "explore" -> "Исследование"
    "compaction" -> "Сжатие контекста"
    "summary" -> "Краткое содержание"
    "title" -> "Заголовки"
    else -> agent.name.ifBlank { agent.id }
}

private fun agentDescription(agent: AgentInfo): String? = when (agent.id.lowercase()) {
    "build" -> "Пишет код, запускает команды и вносит изменения в файлы"
    "plan" -> "Анализирует задачу и составляет план без изменений в файлах"
    "general" -> "Универсальный агент для сложных задач"
    "explore" -> "Быстро ищет файлы и разбирается в проекте"
    "compaction" -> "Сжимает историю чата, чтобы уложиться в контекст"
    else -> agent.description.takeIf { it.isNotBlank() }
}

private fun agentModeLabel(mode: String): String? = when (mode.lowercase()) {
    "primary" -> "основной"
    "subagent" -> "подагент"
    "all" -> "любой"
    else -> mode.takeIf { it.isNotBlank() }
}

private fun variantLabel(variant: String): String = when (variant.lowercase()) {
    "minimal" -> "Минимальный"
    "low" -> "Низкий"
    "medium", "med" -> "Средний"
    "high" -> "Высокий"
    "max", "maximum" -> "Максимальный"
    "none", "off" -> "Без размышлений"
    "auto" -> "Авто"
    "default" -> "Обычный"
    else -> variant.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun ModelRow(
    vm: AppViewModel,
    server: ServerProfile,
    model: ModelInfo,
    active: Boolean,
    selectedVariant: String?,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onPick: (ModelInfo) -> Unit
) {
    val busy = model.ref.key in vm.modelsUpdating
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active && model.enabled) AccentSoft.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(enabled = model.enabled && !busy) { onPick(model) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.title,
                    color = when {
                        !model.enabled -> TextDim
                        active -> Accent
                        else -> TextMain
                    },
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (model.free) {
                    Spacer(Modifier.width(6.dp))
                    Text("free", color = Green, fontSize = 9.sp)
                }
            }
            Text(
                buildString {
                    if (model.contextLimit > 0) append("${model.contextLimit / 1000}k ctx")
                    if (!model.enabled) {
                        if (isNotEmpty()) append(" · ")
                        append("выключена")
                    } else if (active && !selectedVariant.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(variantLabel(selectedVariant))
                    } else if (model.variants.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append("вариантов: ${model.variants.size}")
                    }
                },
                color = TextDim,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (favorite) "Убрать из избранного" else "В избранное",
                tint = if (favorite) Accent else TextDim,
                modifier = Modifier.size(18.dp)
            )
        }
        if (active && model.enabled) {
            Text("✓", color = Accent, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
        }
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Accent
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Switch(
                checked = model.enabled,
                onCheckedChange = { enabled -> vm.setModelEnabled(server, model, enabled) },
                enabled = !busy,
                modifier = Modifier.scale(0.85f)
            )
        }
    }
}
