package ru.opencode.mobile.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.Attachments
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.data.SessionInfo
import ru.opencode.mobile.data.UrlTools
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.Surface2
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

@Composable
fun ChatsScreen(vm: AppViewModel, server: ServerProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by remember(server.id) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SessionInfo?>(null) }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var exportTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var showClearStorage by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    LaunchedEffect(server.id) {
        vm.refreshStorage(server)
        vm.refreshUsage(server)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importChats(server, uri) { result ->
                result.onSuccess(::toast).onFailure { toast(it.message ?: "Ошибка импорта") }
            }
        }
    }

    val exportOneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            vm.exportChat(server, target.id, uri) { result ->
                result.onSuccess(::toast).onFailure { toast(it.message ?: "Ошибка экспорта") }
            }
        }
    }

    val exportAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportAllChats(server, uri) { result ->
                result.onSuccess(::toast).onFailure { toast(it.message ?: "Ошибка экспорта") }
            }
        }
    }

    val status = vm.statusOf(server.id)
    val filtered = vm.sessions.items.filter {
        query.isBlank() || it.displayTitle.contains(query, ignoreCase = true)
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppHeader(
                title = server.name,
                subtitle = buildString {
                    append(server.modeLabel)
                    if (server.mode == ru.opencode.mobile.data.ConnectionMode.TUNNEL) {
                        append(" · защищено SSH")
                    }
                    append(" · ").append(status.version.ifBlank { server.displayHost })
                },
                onBack = { vm.backFromChats() },
                actions = {
                    IconButton(onClick = { vm.refreshSessions(server) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = TextMuted)
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
                                text = { Text("Провайдеры и ключи", color = TextMain) },
                                onClick = { menu = false; vm.openProviders(server.id) }
                            )
                            DropdownMenuItem(
                                text = { Text("Настройки сервера", color = TextMain) },
                                onClick = { menu = false; vm.openEditor(server.id) }
                            )
                            DropdownMenuItem(
                                text = { Text("Резервная копия", color = TextMain) },
                                onClick = { menu = false; vm.openBackup(server.id) }
                            )
                            DropdownMenuItem(
                                text = { Text("Импорт чатов", color = TextMain) },
                                leadingIcon = { Icon(Icons.Filled.Upload, null, tint = TextMuted) },
                                onClick = {
                                    menu = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Экспорт всех чатов", color = TextMain) },
                                leadingIcon = { Icon(Icons.Filled.Download, null, tint = TextMuted) },
                                onClick = {
                                    menu = false
                                    exportAllLauncher.launch(vm.suggestedFileName("opencode-chats"))
                                }
                            )
                        }
                    }
                }
            )

            if (server.mode == ru.opencode.mobile.data.ConnectionMode.DIRECT &&
                UrlTools.isInsecureExternal(server.directUrl)
            ) {
                Surface(
                    color = Color(0xFF2A1414),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        "Прямое подключение по HTTP без шифрования: пароль и переписка " +
                            "могут быть перехвачены. Используйте HTTPS или SSH-туннель.",
                        color = Red,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Поиск по названию", color = TextDim, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextDim) },
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

            val storage = vm.storage
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (vm.chatsCompact) "Хранилище и лимиты" else "Свернуть панель",
                    color = TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { vm.toggleChatsCompact() }
                        .padding(vertical = 2.dp)
                )
                Icon(
                    if (vm.chatsCompact) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (vm.chatsCompact) "Развернуть панель" else "Свернуть панель",
                    tint = TextDim,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { vm.toggleChatsCompact() }
                )
                Spacer(Modifier.weight(1f))
                if (vm.chatsCompact) {
                    val free = storage.info?.freeBytes?.let(Attachments::humanSize)
                    val goWeek = vm.usage.info?.meters?.firstOrNull { it.label == "Неделя" }?.percent
                    val summary = buildList {
                        free?.let { add("Свободно $it") }
                        goWeek?.let { add("Go ${it.toInt()}%") }
                    }.joinToString(" · ")
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            color = TextDim,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (!vm.chatsCompact) {
            Surface(
                color = Surface1,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Border),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Storage, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Хранилище сервера",
                            color = TextMain,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (storage.loading) {
                            CircularProgressIndicator(
                                Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = Accent
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            IconButton(
                                onClick = { vm.refreshStorage(server) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "Обновить место",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                vm.loadUploads(server)
                                showFiles = true
                            },
                            enabled = !storage.loading
                        ) {
                            Text("Файлы", color = Accent, fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = { showClearStorage = true },
                            enabled = !storage.loading
                        ) {
                            Text("Очистить", color = Red, fontSize = 12.sp)
                        }
                    }
                    if (storage.error.isNotBlank()) {
                        Text(
                            storage.error,
                            color = Red,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Свободно", color = TextDim, fontSize = 10.sp)
                                Text(
                                    storage.info?.freeBytes?.let(Attachments::humanSize) ?: "—",
                                    color = TextMain,
                                    fontSize = 13.sp
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Кэш файлов", color = TextDim, fontSize = 10.sp)
                                Text(
                                    storage.info?.uploadsBytes?.let(Attachments::humanSize) ?: "—",
                                    color = TextMain,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            val usage = vm.usage
            if (usage.info != null && !usage.info.isEmpty) {
                Surface(
                    color = Surface1,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Лимиты OpenCode Go",
                                color = TextMain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (usage.loading) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Accent
                                )
                            } else {
                                IconButton(
                                    onClick = { vm.refreshUsage(server) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Обновить лимиты",
                                        tint = TextMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                        usage.info.meters.forEach { meter ->
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(meter.label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "${meter.percent.toInt()}%",
                                    color = if (meter.limited) Red else TextMain,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Surface2)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((meter.percent / 100.0).toFloat().coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(if (meter.limited) Red else Accent)
                                )
                            }
                        }
                        usage.info.budgets.firstOrNull()?.let { budget ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Бюджет: \$" + "%.2f".format(budget.spent) +
                                    " из \$" + "%.2f".format(budget.limit),
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(4.dp))

            if (status.error.isNotBlank() && vm.sessions.error.isBlank()) {
                Surface(
                    color = Color(0xFF2A1414),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(status.error, color = Red, fontSize = 11.sp, lineHeight = 15.sp)
                        TextButton(onClick = { vm.checkStatus(server) }) {
                            Text("Повторить", color = Accent, fontSize = 12.sp)
                        }
                    }
                }
            }

            when {
                vm.sessions.loading && vm.sessions.items.isEmpty() -> LoadingLine("Загружаем чаты…")

                vm.sessions.error.isNotBlank() && vm.sessions.items.isEmpty() -> EmptyState(
                    title = "Не удалось загрузить",
                    text = vm.sessions.error,
                    action = {
                        PrimaryButton(
                            text = "Повторить",
                            onClick = { vm.refreshSessions(server) },
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                    }
                )

                filtered.isEmpty() -> EmptyState(
                    title = if (query.isBlank()) "Чатов пока нет" else "Ничего не найдено",
                    text = if (query.isBlank()) {
                        "Создайте чат и выберите модель. Бесплатные модели работают без ключа, " +
                            "для остальных добавьте API-ключ в разделе «Провайдеры»."
                    } else {
                        "Попробуйте другой запрос."
                    },
                    action = if (query.isBlank()) {
                        {
                            PrimaryButton(
                                text = "Новый чат",
                                onClick = {
                                    vm.createChat(server) { toast(it) }
                                },
                                modifier = Modifier.fillMaxWidth(0.7f)
                            )
                        }
                    } else null
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            onClick = { vm.openChat(server.id, session.id) },
                            onMenu = { menuFor = session }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { vm.createChat(server) { toast(it) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Accent,
            contentColor = Color(0xFF1A1205),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Новый чат")
        }
    }

    menuFor?.let { session ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = {
                Text(
                    session.displayTitle,
                    color = TextMain,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            menuFor = null
                            renameTarget = session
                            renameText = session.displayTitle
                        }
                    ) { Text("Переименовать", color = TextMain) }
                    TextButton(
                        onClick = {
                            menuFor = null
                            exportTarget = session
                            exportOneLauncher.launch(vm.suggestedFileName("opencode-chat"))
                        }
                    ) { Text("Экспорт чата", color = TextMain) }
                    TextButton(
                        onClick = {
                            menuFor = null
                            deleteTarget = session
                        }
                    ) { Text("Удалить чат", color = Red) }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuFor = null }) { Text("Закрыть", color = TextMuted) }
            },
            containerColor = Surface2
        )
    }

    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
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
                    if (title.isNotBlank()) vm.renameChat(server, session.id, title)
                    renameTarget = null
                }) { Text("Сохранить", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Отмена", color = TextMuted) }
            },
            containerColor = Surface2
        )
    }

    deleteTarget?.let { session ->
        ConfirmDialog(
            title = "Удалить чат?",
            text = "«${session.displayTitle}» будет удалён на сервере без возможности восстановления.",
            onConfirm = { vm.deleteChat(server, session) { toast("Чат удалён") } },
            onDismiss = { deleteTarget = null }
        )
    }

    if (showFiles) {
        AlertDialog(
            onDismissRequest = { showFiles = false },
            title = { Text("Файлы на сервере", color = TextMain, fontWeight = FontWeight.SemiBold) },
            text = {
                when {
                    vm.uploadsLoading -> LoadingLine("Загружаем список…")
                    vm.uploads.isEmpty() -> Text(
                        "В ~/opencode-uploads пока нет файлов.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(vm.uploads, key = { it }) { name ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    name,
                                    color = TextMain,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { fileToDelete = name },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Удалить файл",
                                        tint = Red,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFiles = false }) {
                    Text("Закрыть", color = TextMuted)
                }
            },
            containerColor = Surface2
        )
    }

    fileToDelete?.let { name ->
        ConfirmDialog(
            title = "Удалить файл?",
            text = "«$name» будет удалён из ~/opencode-uploads на сервере.",
            onConfirm = {
                vm.deleteUpload(server, name) { result ->
                    result
                        .onSuccess { toast("Файл удалён") }
                        .onFailure { toast(it.message ?: "Не удалось удалить файл") }
                }
            },
            onDismiss = { fileToDelete = null }
        )
    }

    if (showClearStorage) {
        ConfirmDialog(
            title = "Очистить кэш файлов?",
            text = "Будут удалены файлы из ~/opencode-uploads на сервере. " +
                "OpenCode, чаты и настройки провайдеров не затрагиваются.",
            confirmText = "Очистить",
            onConfirm = {
                showClearStorage = false
                vm.clearUploadCache(server) { result ->
                    result
                        .onSuccess { toast("Кэш файлов очищен") }
                        .onFailure { toast(it.message ?: "Не удалось очистить кэш") }
                }
            },
            onDismiss = { showClearStorage = false }
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionInfo,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Surface1,
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.displayTitle,
                    color = TextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        relativeTime(session.updated),
                        color = TextDim,
                        fontSize = 11.sp
                    )
                    session.model?.let { model ->
                        Text(" · ${model.providerID}/${model.id}", color = TextDim, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (session.cost > 0) {
                        Text(" · $${"%.3f".format(session.cost)}", color = TextDim, fontSize = 11.sp)
                    }
                }
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Действия", tint = TextDim)
            }
        }
    }
}
