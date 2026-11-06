package ru.opencode.mobile.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.Attachments
import ru.opencode.mobile.data.ConnectionMode
import ru.opencode.mobile.data.Passwords
import ru.opencode.mobile.data.ServerColors
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.data.ServerStatus
import ru.opencode.mobile.data.UrlTools
import ru.opencode.mobile.net.HostInfo
import ru.opencode.mobile.net.Ssh
import ru.opencode.mobile.net.SshTarget
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

/** Типовой адрес компьютера в туннеле AmneziaWG. */
private const val AWG_DEFAULT_URL = "http://10.8.0.3:4096"

@Composable
fun ServerEditorScreen(
    vm: AppViewModel,
    existing: ServerProfile?,
    modifier: Modifier = Modifier
) {
    val key = existing?.id ?: "new"
    val context = LocalContext.current
    var name by remember(key) { mutableStateOf(existing?.name.orEmpty()) }
    var mode by remember(key) { mutableStateOf(existing?.mode ?: ConnectionMode.TUNNEL) }
    var sshHost by remember(key) { mutableStateOf(existing?.sshHost.orEmpty()) }
    var sshPort by remember(key) { mutableStateOf((existing?.sshPort ?: 22).toString()) }
    var sshUser by remember(key) { mutableStateOf(existing?.sshUser ?: "root") }
    var sshPassword by remember(key) { mutableStateOf(existing?.sshPassword.orEmpty()) }
    var sshPrivateKey by remember(key) { mutableStateOf(existing?.sshPrivateKey.orEmpty()) }
    var sshKeyPassphrase by remember(key) { mutableStateOf(existing?.sshKeyPassphrase.orEmpty()) }
    var keyName by remember(key) {
        mutableStateOf(if (existing?.sshPrivateKey.isNullOrBlank()) "" else "ключ загружен")
    }
    var fingerprint by remember(key) { mutableStateOf(existing?.sshFingerprint.orEmpty()) }
    var remotePort by remember(key) { mutableStateOf((existing?.remotePort ?: 4096).toString()) }
    var directUrl by remember(key) { mutableStateOf(existing?.directUrl.orEmpty()) }
    var allowInsecureHttp by remember(key) { mutableStateOf(existing?.allowInsecureHttp ?: false) }
    var tlsPin by remember(key) { mutableStateOf(existing?.tlsPin.orEmpty()) }
    var localPreset by remember(key) {
        mutableStateOf(
            existing == null || existing.directUrl.isBlank() ||
                UrlTools.isLocalUrl(existing.directUrl)
        )
    }
    var testStatus by remember(key) { mutableStateOf<ServerStatus?>(null) }
    var testing by remember(key) { mutableStateOf(false) }
    var showGuide by remember(key) { mutableStateOf(false) }
    var serverPassword by remember(key) {
        mutableStateOf(existing?.serverPassword?.takeIf { it.isNotBlank() } ?: Passwords.generate())
    }

    var error by remember(key) { mutableStateOf("") }
    var probing by remember(key) { mutableStateOf(false) }
    var probeInfo by remember(key) { mutableStateOf<HostInfo?>(null) }
    var probeError by remember(key) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val keyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                    }
                    if (text.isNullOrBlank()) throw IllegalStateException("Файл ключа пуст")
                    sshPrivateKey = text
                    keyName = Attachments.displayName(context, uri) ?: "SSH-ключ"
                    error = ""
                } catch (e: Exception) {
                    error = e.message ?: "Не удалось прочитать SSH-ключ"
                }
            }
        }
    }

    fun buildProfile(): ServerProfile? {
        val host = sshHost.trim()
        val parsedSshPort = sshPort.toIntOrNull() ?: 22
        val parsedRemotePort = remotePort.toIntOrNull() ?: 4096
        if (parsedSshPort !in 1..65535) {
            error = "Порт SSH должен быть от 1 до 65535"
            return null
        }
        if (parsedRemotePort !in 1..65535) {
            error = "Порт OpenCode должен быть от 1 до 65535"
            return null
        }
        if (mode == ConnectionMode.TUNNEL) {
            if (host.isBlank()) {
                error = "Укажите SSH-хост"
                return null
            }
            if (sshPassword.isBlank() && sshPrivateKey.isBlank()) {
                error = "Укажите пароль SSH или импортируйте приватный ключ"
                return null
            }
        } else {
            val normalized = UrlTools.normalize(directUrl)
            if (normalized == null) {
                error = "Укажите URL OpenCode, например https://server.example:4096"
                return null
            }
            if (UrlTools.isInsecureExternal(normalized) && !allowInsecureHttp) {
                error = "HTTP без шифрования для внешнего адреса запрещён. " +
                    "Используйте HTTPS, SSH-туннель или включите разрешение HTTP ниже."
                return null
            }
        }
        val displayName = name.trim().ifBlank {
            if (mode == ConnectionMode.TUNNEL) {
                host
            } else {
                directUrl.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .trimEnd('/')
            }
        }
        return ServerProfile(
            id = existing?.id ?: Passwords.newId(),
            name = displayName,
            color = existing?.color ?: ServerColors.next(vm.servers),
            mode = mode,
            sshHost = host,
            sshPort = parsedSshPort,
            sshUser = sshUser.trim().ifBlank { "root" },
            sshPassword = sshPassword,
            sshPrivateKey = sshPrivateKey,
            sshKeyPassphrase = sshKeyPassphrase,
            sshFingerprint = fingerprint.trim(),
            remotePort = parsedRemotePort,
            directUrl = directUrl.trim(),
            allowInsecureHttp = allowInsecureHttp,
            tlsPin = tlsPin.trim(),
            serverPassword = serverPassword.trim(),
            defaultModel = existing?.defaultModel.orEmpty(),
            customProviders = existing?.customProviders.orEmpty(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
    }

    fun probe() {
        val host = sshHost.trim()
        if (host.isBlank()) {
            probeError = "Укажите SSH-хост"
            return
        }
        val target = SshTarget(
            host = host,
            port = sshPort.toIntOrNull() ?: 22,
            user = sshUser.trim().ifBlank { "root" },
            password = sshPassword,
            privateKey = sshPrivateKey,
            keyPassphrase = sshKeyPassphrase
        )
        scope.launch {
            probing = true
            probeError = ""
            probeInfo = null
            try {
                val info = Ssh.probe(target)
                probeInfo = info
                if (fingerprint.isBlank()) fingerprint = info.fingerprint
            } catch (e: Exception) {
                probeError = e.message ?: "Не удалось проверить сервер"
            } finally {
                probing = false
            }
        }
    }

    fun testDirect() {
        val normalized = UrlTools.normalize(directUrl)
        if (normalized == null) {
            testStatus = ServerStatus(online = false, error = "Укажите URL OpenCode")
            return
        }
        if (UrlTools.isInsecureExternal(normalized) && !allowInsecureHttp) {
            testStatus = ServerStatus(
                online = false,
                error = "Сначала разрешите HTTP без шифрования или используйте HTTPS"
            )
            return
        }
        if (serverPassword.isBlank()) {
            testStatus = ServerStatus(online = false, error = "Введите пароль API OpenCode")
            return
        }
        testing = true
        testStatus = null
        val probeProfile = ServerProfile(
            id = "test-${existing?.id ?: "new"}",
            name = "Проверка",
            color = 0,
            mode = ConnectionMode.DIRECT,
            directUrl = directUrl.trim(),
            allowInsecureHttp = allowInsecureHttp,
            tlsPin = tlsPin.trim(),
            serverPassword = serverPassword.trim()
        )
        vm.testConnection(probeProfile) { result ->
            testing = false
            testStatus = result
        }
    }

    Column(modifier.fillMaxSize()) {
        AppHeader(
            title = if (existing == null) "Новый сервер" else "Сервер",
            subtitle = if (existing == null) "Подключение к OpenCode" else existing.name,
            onBack = { vm.openServers() }
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            LabeledField(
                label = "Название",
                value = name,
                onValueChange = { name = it },
                placeholder = "Мой сервер"
            )

            Text("Подключение", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("SSH-туннель", mode == ConnectionMode.TUNNEL) { mode = ConnectionMode.TUNNEL }
                ModeChip("Прямой URL", mode == ConnectionMode.DIRECT) { mode = ConnectionMode.DIRECT }
            }
            Spacer(Modifier.height(14.dp))

            if (mode == ConnectionMode.TUNNEL) {
                LabeledField(
                    label = "SSH-хост",
                    value = sshHost,
                    onValueChange = { sshHost = it },
                    placeholder = "194.87.197.248 или server.example"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField(
                        label = "Порт SSH",
                        value = sshPort,
                        onValueChange = { sshPort = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    )
                    LabeledField(
                        label = "Пользователь",
                        value = sshUser,
                        onValueChange = { sshUser = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                LabeledField(
                    label = "Пароль SSH",
                    value = sshPassword,
                    onValueChange = { sshPassword = it },
                    secret = true,
                    hint = "Можно оставить пустым, если импортирован приватный ключ."
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(
                        text = if (keyName.isBlank()) "Импортировать SSH-ключ" else "Ключ: $keyName",
                        onClick = { keyLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    if (sshPrivateKey.isNotBlank()) {
                        TextButton(onClick = {
                            sshPrivateKey = ""
                            sshKeyPassphrase = ""
                            keyName = ""
                        }) {
                            Text("Убрать", color = Red, fontSize = 12.sp)
                        }
                    }
                }
                if (sshPrivateKey.isNotBlank()) {
                    LabeledField(
                        label = "Пароль ключа (если есть)",
                        value = sshKeyPassphrase,
                        onValueChange = { sshKeyPassphrase = it },
                        secret = true,
                        hint = "Хранится в зашифрованном виде вместе с ключом."
                    )
                }
                LabeledField(
                    label = "Порт OpenCode на сервере",
                    value = remotePort,
                    onValueChange = { remotePort = it.filter(Char::isDigit) },
                    keyboardType = KeyboardType.Number,
                    hint = "По умолчанию 4096. Проброс идёт на 127.0.0.1 сервера."
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(
                        text = if (probing) "Проверяем…" else "Проверить SSH",
                        onClick = ::probe,
                        modifier = Modifier.weight(1f),
                        enabled = !probing
                    )
                    Spacer(Modifier.height(0.dp))
                }
                if (probeError.isNotBlank()) {
                    Text(probeError, color = Red, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                probeInfo?.let { info ->
                    SectionCard {
                        Text("Сервер доступен", color = Accent, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        InfoLine("Система", info.osName)
                        InfoLine("Архитектура", info.arch)
                        InfoLine("root", if (info.isRoot) "да" else "нет")
                        InfoLine("systemd", if (info.hasSystemd) "да" else "нет")
                        InfoLine("OpenCode", info.opencodeVersion.ifBlank { "не установлен" })
                        InfoLine("Свободно", info.freeDisk)
                        Spacer(Modifier.height(6.dp))
                        Text(info.fingerprint, color = TextDim, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                LabeledField(
                    label = "Отпечаток SSH-ключа",
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    hint = "Заполнится после проверки. Защищает от подмены сервера."
                )
            } else {
                Text("Способ подключения", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Локальная сеть / AWG", localPreset) {
                        localPreset = true
                        if (directUrl.isBlank() || !UrlTools.isLocalUrl(directUrl)) {
                            directUrl = AWG_DEFAULT_URL
                        }
                        testStatus = null
                    }
                    ModeChip("Другой адрес", !localPreset) {
                        localPreset = false
                        testStatus = null
                    }
                }
                TextButton(onClick = { showGuide = true }) {
                    Text("Инструкция: сервер и вкладки", color = Accent, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))

                LabeledField(
                    label = "URL OpenCode",
                    value = directUrl,
                    onValueChange = {
                        directUrl = it
                        testStatus = null
                    },
                    placeholder = if (localPreset) AWG_DEFAULT_URL else "https://server.example:4096",
                    hint = if (localPreset) {
                        "Адрес компьютера в туннеле AmneziaWG и порт OpenCode. " +
                            "Если 10.8.0.3 не отвечает, проверьте адрес ПК в конфигурации AWG."
                    } else {
                        "Для внешнего доступа используйте HTTPS."
                    }
                )
                val normalized = UrlTools.normalize(directUrl)
                val insecure = normalized != null && UrlTools.isInsecureExternal(normalized)
                val https = normalized?.startsWith("https://", ignoreCase = true) == true
                if (insecure) {
                    Text(
                        "Внимание: HTTP передаёт пароль и переписку открыто. " +
                            "Включайте это только для доверенной сети.",
                        color = Red,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Разрешить HTTP без шифрования",
                            color = TextMain,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = allowInsecureHttp,
                            onCheckedChange = {
                                allowInsecureHttp = it
                                testStatus = null
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (https) {
                    LabeledField(
                        label = "SHA-256 пин сертификата (необязательно)",
                        value = tlsPin,
                        onValueChange = {
                            tlsPin = it
                            testStatus = null
                        },
                        placeholder = "sha256/AAAAAAAA…",
                        hint = "Защищает от подмены HTTPS-сертификата. Оставьте пустым, если не уверены."
                    )
                }
            }

            LabeledField(
                label = "Пароль API OpenCode",
                value = serverPassword,
                onValueChange = {
                    serverPassword = it
                    testStatus = null
                },
                secret = true,
                hint = "OPENCODE_PASSWORD на сервере. Для локального туннеля пароль обязателен."
            )

            if (mode == ConnectionMode.DIRECT) {
                SecondaryButton(
                    text = if (testing) "Проверяем…" else "Проверить подключение",
                    onClick = ::testDirect,
                    enabled = !testing
                )
                testStatus?.let { status ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (status.online) {
                            "Подключено. OpenCode ${status.version.ifBlank { "—" }}"
                        } else {
                            status.error.ifBlank { "Не удалось подключиться" }
                        },
                        color = if (status.online) Accent else Red,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (existing != null) {
                val status = vm.statusOf(existing.id)
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(status)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.checkStatus(existing) }) {
                            Text("Проверить API", color = Accent)
                        }
                    }
                    if (status.error.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(status.error, color = Red, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (error.isNotBlank()) {
                Text(error, color = Red, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
            }

            PrimaryButton(
                text = if (existing == null) "Добавить сервер" else "Сохранить",
                onClick = {
                    error = ""
                    buildProfile()?.let { vm.saveServer(it) }
                }
            )
            Spacer(Modifier.height(10.dp))

            if (mode == ConnectionMode.TUNNEL && sshHost.isNotBlank() &&
                (sshPassword.isNotBlank() || sshPrivateKey.isNotBlank())
            ) {
                SecondaryButton(
                    text = "Установить OpenCode на этот сервер",
                    onClick = {
                        error = ""
                        buildProfile()?.let { profile ->
                            vm.saveServer(profile, navigate = false)
                            vm.openInstall(profile.id)
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }

            if (existing != null) {
                SecondaryButton(
                    text = "Удалить сервер",
                    onClick = { confirmDelete = true }
                )
                Spacer(Modifier.height(10.dp))
            }

            Text(
                "Пароли, SSH-ключ и API-пароль хранятся только на устройстве в зашифрованном виде. " +
                    "Резервная копия может включать их — экспортируйте её осознанно.",
                color = TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showGuide) {
        GuideDialog(vm) { showGuide = false }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Удалить сервер?",
            text = "Профиль «${existing.name}» будет удалён. OpenCode и чаты на сервере останутся.",
            onConfirm = {
                vm.deleteServer(existing)
                vm.openServers()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Accent else Surface1,
        contentColor = if (selected) androidx.compose.ui.graphics.Color(0xFF1A1205) else TextMuted,
        border = BorderStroke(1.dp, if (selected) Accent else Border)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextMuted, fontSize = 12.sp)
    }
}
