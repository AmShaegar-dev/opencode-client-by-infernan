package ru.opencode.mobile.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.data.ConnectionMode
import ru.opencode.mobile.data.Passwords
import ru.opencode.mobile.data.ServerColors
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.net.HostInfo
import ru.opencode.mobile.net.InstallEvent
import ru.opencode.mobile.net.InstallRequest
import ru.opencode.mobile.net.Ssh
import ru.opencode.mobile.net.SshTarget
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.Bg
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Green
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

@Composable
fun InstallScreen(vm: AppViewModel, existing: ServerProfile?, modifier: Modifier = Modifier) {
    var host by remember { mutableStateOf(existing?.sshHost.orEmpty()) }
    var port by remember { mutableStateOf((existing?.sshPort ?: 22).toString()) }
    var user by remember { mutableStateOf(existing?.sshUser ?: "root") }
    var sshPassword by remember { mutableStateOf(existing?.sshPassword.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var serverPassword by remember {
        mutableStateOf(existing?.serverPassword?.takeIf { it.isNotBlank() } ?: Passwords.generate())
    }
    var openCodePort by remember { mutableStateOf((existing?.remotePort ?: 4096).toString()) }
    var bindAll by remember { mutableStateOf(false) }
    var workDir by remember { mutableStateOf("/root") }

    var fingerprint by remember { mutableStateOf(existing?.sshFingerprint.orEmpty()) }
    var probing by remember { mutableStateOf(false) }
    var hostInfo by remember { mutableStateOf<HostInfo?>(null) }
    var probeError by remember { mutableStateOf("") }

    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf("") }
    var finishedVersion by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val logScroll = rememberScrollState()

    fun target(): SshTarget = SshTarget(
        host = host.trim(),
        port = port.toIntOrNull() ?: 22,
        user = user.trim().ifBlank { "root" },
        password = sshPassword,
        privateKey = existing?.sshPrivateKey.orEmpty(),
        keyPassphrase = existing?.sshKeyPassphrase.orEmpty()
    )

    fun probe() {
        if (host.isBlank()) {
            probeError = "Укажите SSH-хост"
            return
        }
        scope.launch {
            probing = true
            probeError = ""
            hostInfo = null
            try {
                val info = Ssh.probe(target())
                hostInfo = info
                fingerprint = info.fingerprint
                if (!info.isRoot) probeError = "Пользователь не root — установка не сможет создать службу."
            } catch (e: Exception) {
                probeError = e.message ?: "Не удалось подключиться"
            } finally {
                probing = false
            }
        }
    }

    fun startInstall() {
        error = ""
        if (host.isBlank() || sshPassword.isBlank()) {
            error = "Укажите SSH-хост и пароль"
            return
        }
        if (fingerprint.isBlank()) {
            error = "Сначала проверьте сервер, чтобы закрепить SSH-ключ"
            return
        }
        if (!Passwords.isValidServerPassword(serverPassword)) {
            error = "Пароль API: 6–128 символов, латиница, цифры, _ и -"
            return
        }
        val parsedPort = openCodePort.toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535) {
            error = "Некорректный порт OpenCode"
            return
        }
        val request = InstallRequest(
            target = target(),
            fingerprint = fingerprint,
            serverPassword = serverPassword,
            port = parsedPort,
            bindAll = bindAll,
            workDir = workDir.trim().ifBlank { "/root" }
        )
        scope.launch {
            installing = true
            finishedVersion = ""
            logs = emptyList()
            progress = 0f
            stage = "Подготовка…"
            try {
                Ssh.install(request).collect { event ->
                    when (event) {
                        is InstallEvent.Stage -> {
                            stage = event.text
                            progress = event.progress
                        }

                        is InstallEvent.Log -> {
                            logs = (logs + event.line).takeLast(500)
                        }

                        is InstallEvent.Done -> {
                            finishedVersion = event.version
                            stage = "Готово"
                            progress = 1f
                        }
                    }
                    if (event is InstallEvent.Log) {
                        runCatching { logScroll.animateScrollTo(logScroll.maxValue) }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Ошибка установки"
                stage = "Остановлено"
            } finally {
                installing = false
            }

            if (finishedVersion.isNotBlank()) {
                val profile = ServerProfile(
                    id = existing?.id ?: Passwords.newId(),
                    name = name.trim().ifBlank { host.trim() },
                    color = existing?.color ?: ServerColors.next(vm.servers),
                    mode = ConnectionMode.TUNNEL,
                    sshHost = host.trim(),
                    sshPort = port.toIntOrNull() ?: 22,
                    sshUser = user.trim().ifBlank { "root" },
                    sshPassword = sshPassword,
                    sshFingerprint = fingerprint,
                    remotePort = parsedPort,
                    directUrl = "",
                    serverPassword = serverPassword,
                    defaultModel = existing?.defaultModel.orEmpty(),
                    customProviders = existing?.customProviders.orEmpty(),
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                vm.saveServer(profile, navigate = false)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        AppHeader(
            title = "Установка OpenCode",
            subtitle = if (existing == null) "Новый сервер по SSH" else existing.name,
            onBack = { if (!installing) vm.openServers() }
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (finishedVersion.isBlank()) {
                LabeledField(
                    label = "Название",
                    value = name,
                    onValueChange = { name = it },
                    placeholder = host.ifBlank { "Мой сервер" }
                )
                LabeledField(
                    label = "SSH-хост",
                    value = host,
                    onValueChange = { host = it },
                    placeholder = "194.87.197.248",
                    enabled = !installing
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField(
                        label = "Порт SSH",
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                        enabled = !installing
                    )
                    LabeledField(
                        label = "Пользователь",
                        value = user,
                        onValueChange = { user = it },
                        modifier = Modifier.weight(1f),
                        enabled = !installing
                    )
                }
                LabeledField(
                    label = "Пароль SSH",
                    value = sshPassword,
                    onValueChange = { sshPassword = it },
                    secret = true,
                    enabled = !installing
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(
                        text = when {
                            probing -> "Проверяем…"
                            hostInfo != null -> "Проверить ещё раз"
                            else -> "Проверить сервер"
                        },
                        onClick = ::probe,
                        modifier = Modifier.weight(1f),
                        enabled = !probing && !installing
                    )
                }
                if (probeError.isNotBlank()) {
                    Text(probeError, color = Red, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                hostInfo?.let { info ->
                    SectionCard {
                        Text("Сервер доступен", color = Green, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        InfoRow("Система", info.osName)
                        InfoRow("Архитектура", info.arch)
                        InfoRow("root", if (info.isRoot) "да" else "нет")
                        InfoRow("systemd", if (info.hasSystemd) "да" else "нет")
                        InfoRow("OpenCode", info.opencodeVersion.ifBlank { "не установлен" })
                        InfoRow("Свободно", info.freeDisk)
                        Spacer(Modifier.height(6.dp))
                        Text(fingerprint, color = TextDim, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                }

                LabeledField(
                    label = "Порт OpenCode",
                    value = openCodePort,
                    onValueChange = { openCodePort = it.filter(Char::isDigit) },
                    keyboardType = KeyboardType.Number,
                    enabled = !installing
                )
                LabeledField(
                    label = "Пароль API OpenCode",
                    value = serverPassword,
                    onValueChange = { serverPassword = it },
                    secret = true,
                    enabled = !installing,
                    hint = "Сохранится в /etc/opencode/server.env и в приложении. Используется для Basic Auth."
                )
                LabeledField(
                    label = "Рабочая папка",
                    value = workDir,
                    onValueChange = { workDir = it },
                    placeholder = "/root",
                    enabled = !installing,
                    hint = "Корень проекта, с которым будет работать OpenCode."
                )

                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Открыть порт наружу", color = TextMain, fontSize = 14.sp)
                            Text(
                                "0.0.0.0 вместо 127.0.0.1. Нужно только для прямого подключения без туннеля.",
                                color = TextDim,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        Switch(
                            checked = bindAll,
                            onCheckedChange = { bindAll = it },
                            enabled = !installing,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF1A1205),
                                checkedTrackColor = Accent,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = Surface1
                            )
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (error.isNotBlank()) {
                    Text(error, color = Red, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }

                PrimaryButton(
                    text = if (installing) "Установка…" else "Установить OpenCode",
                    onClick = ::startInstall,
                    enabled = !installing,
                    loading = installing
                )
                Spacer(Modifier.height(12.dp))
            }

            if (stage.isNotBlank() || logs.isNotEmpty()) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stage, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${(progress * 100).toInt()}%", color = Accent, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Accent,
                        trackColor = Border
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(Bg, RoundedCornerShape(10.dp))
                            .verticalScroll(logScroll)
                            .padding(10.dp)
                    ) {
                        logs.forEach { line ->
                            Text(
                                line,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (finishedVersion.isNotBlank()) {
                SectionCard {
                    Text("OpenCode установлен", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Версия $finishedVersion. Служба запущена, пароль API сохранён в профиле.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        text = "Перейти к чатам",
                        onClick = {
                            val profile = vm.servers.firstOrNull {
                                it.sshHost == host.trim() && it.sshPort == (port.toIntOrNull() ?: 22)
                            }
                            if (profile != null) vm.openChats(profile.id) else vm.openServers()
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                "Установщик скачивает официальный OpenCode V2 (opencode.ai/v2/install), " +
                    "создаёт systemd-службу opencode и проверяет /api/info. " +
                    "При повторной установке служба и пароль обновляются.",
                color = TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextMuted, fontSize = 12.sp)
    }
}
