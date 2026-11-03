package ru.opencode.mobile.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

@Composable
fun BackupScreen(vm: AppViewModel, serverId: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val server = vm.serverById(serverId)

    var includeSecrets by remember { mutableStateOf(false) }
    var exportAllBusy by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    fun report(result: Result<String>) {
        result
            .onSuccess(::toast)
            .onFailure { toast(it.message ?: "Ошибка резервного копирования") }
    }

    // Чаты: экспорт/импорт для конкретного сервера.
    val chatsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val target = server
        if (uri != null && target != null) {
            exportAllBusy = true
            vm.exportAllChats(target, uri) { result ->
                exportAllBusy = false
                report(result)
            }
        }
    }

    val chatsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = server
        if (uri != null && target != null) {
            importBusy = true
            vm.importChats(target, uri) { result ->
                importBusy = false
                report(result)
            }
        }
    }

    // Приложение: полная резервная копия (серверы и пароли).
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportAllBusy = true
            vm.exportBackup(uri, includeSecrets) { result ->
                exportAllBusy = false
                report(result)
            }
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importBusy = true
            vm.importBackup(uri) { result ->
                importBusy = false
                report(result)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        AppHeader(
            title = "Данные",
            subtitle = server?.name ?: "Резервные копии и чаты",
            onBack = { if (server != null) vm.openChats(server.id) else vm.openServers() }
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (server != null) {
                ChatsBackupCard(
                    server = server,
                    exportBusy = exportAllBusy,
                    importBusy = importBusy,
                    onExportAll = {
                        chatsExportLauncher.launch(vm.suggestedFileName("opencode-chats"))
                    },
                    onImport = {
                        chatsImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            SectionCard {
                Text(
                    "Резервная копия приложения",
                    color = TextMain,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Сохраняет список серверов, режимы подключения, отпечатки SSH и " +
                        "пользовательских провайдеров в JSON-файл.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Включить пароли", color = TextMain, fontSize = 13.sp)
                        Text(
                            "Пароли SSH и API попадут в файл в открытом виде. " +
                                "Храните копию в надёжном месте.",
                            color = TextDim,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = includeSecrets,
                        onCheckedChange = { includeSecrets = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF1A1205),
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = Surface1
                        )
                    )
                }

                if (includeSecrets) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFF2A1414),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Файл с паролями не защищён шифрованием приложения. " +
                                "Передавайте его только по доверенному каналу.",
                            color = Red,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = if (exportAllBusy) "Сохраняем…" else "Экспортировать копию",
                    onClick = {
                        backupExportLauncher.launch(vm.suggestedFileName("opencode-backup"))
                    },
                    enabled = !exportAllBusy && !importBusy,
                    loading = exportAllBusy
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    text = if (importBusy) "Загружаем…" else "Импортировать копию",
                    onClick = {
                        backupImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    enabled = !exportAllBusy && !importBusy
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "При импорте серверы с совпадающим ID или названием заменяются.",
                    color = TextDim,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard {
                Text("Форматы файлов", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                FormatLine(
                    "opencode-backup_*.json",
                    "Серверы приложения и пользовательские провайдеры."
                )
                FormatLine(
                    "opencode-chats_*.json",
                    "Экспорт всех чатов сервера (формат OpenCode: info + messages)."
                )
                FormatLine(
                    "opencode-chat_*.json",
                    "Один чат из списка или из открытого чата."
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Экспорт чатов выполняется через API OpenCode на сервере, импорт — " +
                    "через /api/experimental/session/import. Экспортированный файл можно " +
                    "загрузить в другой сервер OpenCode.",
                color = TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ChatsBackupCard(
    server: ServerProfile,
    exportBusy: Boolean,
    importBusy: Boolean,
    onExportAll: () -> Unit,
    onImport: () -> Unit
) {
    SectionCard {
        Text("Чаты сервера", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "${server.name} · ${server.displayHost}",
            color = TextMuted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = if (exportBusy) "Экспортируем…" else "Экспортировать все чаты",
            onClick = onExportAll,
            enabled = !exportBusy && !importBusy,
            loading = exportBusy
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            text = if (importBusy) "Импортируем…" else "Импортировать чаты",
            onClick = onImport,
            enabled = !exportBusy && !importBusy
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Экспорт идёт последовательно по всем сессиям; чаты с ошибкой пропускаются и " +
                "считаются в итоговом сообщении.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        if (server.sshHost.isBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Для импорта пользовательских провайдеров нужен SSH-доступ в профиле сервера.",
                color = Border,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun FormatLine(name: String, description: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            name,
            color = Accent,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            description,
            color = TextDim,
            fontSize = 11.sp,
            modifier = Modifier.weight(1.3f)
        )
    }
}
