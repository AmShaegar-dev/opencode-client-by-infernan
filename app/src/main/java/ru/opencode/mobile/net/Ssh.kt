package ru.opencode.mobile.net

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Base64

data class SshTarget(
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    val password: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = ""
) {
    val hasAuth: Boolean get() = password.isNotBlank() || privateKey.isNotBlank()
}

data class HostInfo(
    val fingerprint: String,
    val osName: String,
    val arch: String,
    val isRoot: Boolean,
    val hasSystemd: Boolean,
    val opencodeVersion: String,
    val freeDisk: String
)

data class InstallRequest(
    val target: SshTarget,
    val fingerprint: String,
    val serverPassword: String,
    val port: Int = 4096,
    val bindAll: Boolean = false,
    val workDir: String = "/root"
)

sealed interface InstallEvent {
    data class Stage(val text: String, val progress: Float) : InstallEvent
    data class Log(val line: String) : InstallEvent
    data class Done(val version: String) : InstallEvent
}

/**
 * SSH-слой: проверка сервера, установка OpenCode и локальный проброс порта.
 * Пароль нигде не логируется и не попадает в вывод команд.
 */
object Ssh {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val INSTALL_TIMEOUT_MS = 20 * 60 * 1000L
    private const val INSTALL_URL = "https://opencode.ai/v2/install"

    // ------------------------------------------------------------------
    // Проверка сервера
    // ------------------------------------------------------------------

    suspend fun probe(target: SshTarget): HostInfo = withContext(Dispatchers.IO) {
        val capture = CapturingHostKeys()
        val session = connect(target, capture)
        try {
            val key = capture.key ?: throw IllegalStateException("Сервер не прислал ключ хоста")
            val osName = runCommand(
                session,
                "sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | tr -d '\"' | head -1"
            ).trim().ifBlank { "неизвестна" }
            val arch = runCommand(session, "uname -m").trim().ifBlank { "—" }
            val uid = runCommand(session, "id -u").trim()
            val systemd = runCommand(
                session,
                "if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then echo yes; else echo no; fi"
            ).contains("yes")
            val version = runCommand(
                session,
                "export HOME=\$(getent passwd \$(id -u) | cut -d: -f6); " +
                    "export PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; " +
                    "command -v opencode >/dev/null 2>&1 && opencode --version 2>/dev/null | head -1 || echo ''"
            ).trim()
            val disk = runCommand(session, "df -h / | awk 'NR==2 {print \$4}'").trim()

            HostInfo(
                fingerprint = sha256(key),
                osName = osName,
                arch = arch,
                isRoot = uid == "0",
                hasSystemd = systemd,
                opencodeVersion = version,
                freeDisk = disk
            )
        } finally {
            session.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Установка
    // ------------------------------------------------------------------

    fun install(request: InstallRequest): Flow<InstallEvent> = flow {
        emit(InstallEvent.Stage("Подключаемся к ${request.target.host}…", 0.02f))
        val session = connect(request.target, PinnedHostKeys(request.fingerprint))
        try {
            emit(InstallEvent.Log("SSH-соединение установлено, отпечаток сервера совпал."))

            emit(InstallEvent.Stage("Проверяем систему…", 0.06f))
            val uid = runCommand(session, "id -u").trim()
            if (uid != "0") {
                throw IllegalStateException(
                    "Нужен пользователь root: установщик пишет systemd-службу и ставит пакеты."
                )
            }
            val osName = runCommand(
                session,
                "sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | tr -d '\"'"
            ).trim()
            emit(InstallEvent.Log("Система: ${osName.ifBlank { "неизвестна" }}"))

            val hasSystemd = runCommand(
                session,
                "if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then echo yes; else echo no; fi"
            ).contains("yes")
            emit(
                InstallEvent.Log(
                    if (hasSystemd) "systemd найден — служба будет запускаться автоматически."
                    else "systemd не найден — запустим OpenCode в фоне (без автозапуска)."
                )
            )

            emit(InstallEvent.Stage("Готовим зависимости…", 0.12f))
            withTimeout(INSTALL_TIMEOUT_MS) {
                val depsCode = execStreaming(
                    session,
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "if ! command -v curl >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1; then " +
                        "apt-get update -qq && apt-get install -y -qq curl ca-certificates tar; fi"
                ) { line -> emit(InstallEvent.Log(line)) }
                if (depsCode != 0) {
                    throw IllegalStateException("Не удалось установить curl/tar/ca-certificates (код $depsCode).")
                }

                emit(InstallEvent.Stage("Скачиваем и устанавливаем OpenCode…", 0.25f))
                val installCode = execStreaming(
                    session,
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "export HOME=\$(getent passwd \$(id -u) | cut -d: -f6); " +
                        "export PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; " +
                        "curl -fsSL --retry 3 --connect-timeout 20 $INSTALL_URL | bash"
                ) { line -> emit(InstallEvent.Log(line)) }
                if (installCode != 0) {
                    throw IllegalStateException(
                        "Установщик OpenCode завершился с кодом $installCode. " +
                            "Проверьте, что серверу доступен интернет и opencode.ai."
                    )
                }

                emit(InstallEvent.Stage("Настраиваем службу…", 0.62f))
                val bindHost = if (request.bindAll) "0.0.0.0" else "127.0.0.1"
                val setup = buildSetupScript(
                    request = request,
                    bindHost = bindHost,
                    hasSystemd = hasSystemd
                )
                val setupCode = execStreaming(session, setup) { line -> emit(InstallEvent.Log(line)) }
                if (setupCode != 0) {
                    throw IllegalStateException(
                        "Не удалось настроить службу (код $setupCode). Подробности в журнале выше."
                    )
                }

                emit(InstallEvent.Stage("Проверяем API OpenCode…", 0.88f))
                val info = runCommand(
                    session,
                    "for i in \$(seq 1 40); do " +
                        "if curl -fsS --max-time 3 -u ${shellQuote("opencode:" + request.serverPassword)} " +
                        "http://127.0.0.1:${request.port}/api/info >/tmp/opencode-info.json 2>/dev/null; then " +
                        "cat /tmp/opencode-info.json; exit 0; fi; sleep 1; done; " +
                        "echo '--- opencode log ---'; tail -n 40 /var/log/opencode.log 2>/dev/null; " +
                        "echo '--- systemd ---'; systemctl status opencode --no-pager -n 30 2>/dev/null; exit 1"
                )
                val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                    .find(info)?.groupValues?.get(1).orEmpty()
                if (version.isBlank()) {
                    throw IllegalStateException(
                        "OpenCode не ответил на /api/info. Проверьте журнал:\n" +
                            info.takeLast(2500)
                    )
                }

                emit(InstallEvent.Log("OpenCode $version отвечает на API."))
                if (request.bindAll) {
                    emit(
                        InstallEvent.Log(
                            "Сервер слушает 0.0.0.0:${request.port}. " +
                                "Откройте порт в firewall, если нужен прямой доступ."
                        )
                    )
                }
                emit(InstallEvent.Stage("Готово", 1f))
                emit(InstallEvent.Done(version))
            }
        } finally {
            session.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Команда настройки: env-файл, systemd-юнит (или nohup), запуск. */
    private fun buildSetupScript(
        request: InstallRequest,
        bindHost: String,
        hasSystemd: Boolean
    ): String {
        val workDir = request.workDir.trim().ifBlank { "/root" }
        val safeWorkDir = if (workDir.startsWith("/")) workDir else "/root"
        val pass = request.serverPassword
        require(pass.matches(Regex("[A-Za-z0-9_-]{6,128}"))) {
            "Пароль сервера должен состоять из латинских букв, цифр, _ и - (6–128 символов)."
        }
        return buildString {
            append("set -e\n")
            append("export HOME=\$(getent passwd \$(id -u) | cut -d: -f6)\n")
            append("[ -n \"\$HOME\" ] || export HOME=/root\n")
            append("export PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"\n")
            append("BIN=\"\$(command -v opencode || true)\"\n")
            append("[ -n \"\$BIN\" ] || BIN=\"\$HOME/.opencode/bin/opencode\"\n")
            append("[ -x \"\$BIN\" ] || { echo 'opencode не найден после установки'; exit 12; }\n")
            append("mkdir -p ${shellQuote(safeWorkDir)}\n")
            append("install -d -m 700 /etc/opencode\n")
            append("cat > /etc/opencode/server.env <<'ENVEOF'\n")
            append("OPENCODE_PASSWORD=").append(pass).append("\n")
            append("ENVEOF\n")
            append("chmod 600 /etc/opencode/server.env\n")
            if (hasSystemd) {
                // Делимитер без кавычек: systemd не раскрывает переменные,
                // поэтому путь к бинарнику подставляем на этапе установки.
                append("cat > /etc/systemd/system/opencode.service <<UNITEOF\n")
                append("[Unit]\n")
                append("Description=OpenCode server (managed by OpenCode Mobile)\n")
                append("After=network-online.target\n")
                append("Wants=network-online.target\n\n")
                append("[Service]\n")
                append("Type=simple\n")
                append("User=root\n")
                append("Environment=HOME=").append(safeWorkDir).append("\n")
                append("EnvironmentFile=/etc/opencode/server.env\n")
                append("WorkingDirectory=").append(safeWorkDir).append("\n")
                append("ExecStart=").append("\$BIN").append(" serve --hostname ")
                    .append(bindHost).append(" --port ").append(request.port).append("\n")
                append("Restart=always\n")
                append("RestartSec=3\n")
                append("LimitNOFILE=65536\n\n")
                append("[Install]\n")
                append("WantedBy=multi-user.target\n")
                append("UNITEOF\n")
                append("systemctl daemon-reload\n")
                append("systemctl enable opencode >/dev/null 2>&1 || true\n")
                append("systemctl restart opencode\n")
            } else {
                append("if [ -f /run/opencode.pid ]; then kill \$(cat /run/opencode.pid) 2>/dev/null || true; fi\n")
                append(
                    "nohup env HOME=" + shellQuote(safeWorkDir) +
                        " OPENCODE_PASSWORD=" + pass +
                        " \"\$BIN\" serve --hostname " + bindHost + " --port " + request.port +
                        " >>/var/log/opencode.log 2>&1 &\n"
                )
                append("echo \$! > /run/opencode.pid\n")
                append("sleep 1\n")
            }
        }
    }

    // ------------------------------------------------------------------
    // Конфигурация OpenCode на сервере
    // ------------------------------------------------------------------

    data class RemoteConfig(val path: String, val content: String)

    suspend fun readConfig(target: SshTarget, fingerprint: String = ""): RemoteConfig =
        withContext(Dispatchers.IO) {
            val repository = if (fingerprint.isBlank()) CapturingHostKeys() else PinnedHostKeys(fingerprint)
            val session = connect(target, repository)
            try {
                val path = runCommand(
                    session,
                    "export HOME=\$(getent passwd \$(id -u) | cut -d: -f6); [ -n \"\$HOME\" ] || export HOME=/root; " +
                        "if [ -f \"\$HOME/.config/opencode/opencode.jsonc\" ]; then " +
                        "echo \"\$HOME/.config/opencode/opencode.jsonc\"; " +
                        "elif [ -f \"\$HOME/.config/opencode/opencode.json\" ]; then " +
                        "echo \"\$HOME/.config/opencode/opencode.json\"; " +
                        "else echo \"\$HOME/.config/opencode/opencode.json\"; fi"
                ).trim().lineSequence().firstOrNull().orEmpty()
                val content = runCommand(session, "cat ${shellQuote(path)} 2>/dev/null || true")
                RemoteConfig(path, content.trim())
            } finally {
                session.disconnect()
            }
        }

    suspend fun writeConfig(
        target: SshTarget,
        fingerprint: String,
        path: String,
        content: String
    ) = withContext(Dispatchers.IO) {
        val repository = if (fingerprint.isBlank()) CapturingHostKeys() else PinnedHostKeys(fingerprint)
        val session = connect(target, repository)
        try {
            val quotedPath = shellQuote(path)
            val dir = path.substringBeforeLast('/', "")
            val command = buildString {
                if (dir.isNotBlank()) append("mkdir -p ").append(shellQuote(dir)).append("; ")
                append("printf '%s' ").append(shellQuote(content)).append(" > ").append(quotedPath)
                append("; chmod 600 ").append(quotedPath)
            }
            val output = runCommand(session, command)
            if (output.contains("Permission denied") || output.contains("Read-only file system")) {
                throw IllegalStateException(output.trim())
            }
        } finally {
            session.disconnect()
        }
    }

    /**
     * Загружает файл на сервер через SFTP и возвращает полный путь.
     * По умолчанию файлы складываются в `~/opencode-uploads`.
     */
    suspend fun uploadFile(
        target: SshTarget,
        fingerprint: String,
        remoteDir: String,
        name: String,
        bytes: ByteArray
    ): String = uploadFile(target, fingerprint, remoteDir, name) { ByteArrayInputStream(bytes) }

    /**
     * Загружает файл на сервер потоком, не загружая его целиком в память.
     * [open] вызывается на IO-потоке и должен вернуть новый InputStream.
     */
    suspend fun uploadFile(
        target: SshTarget,
        fingerprint: String,
        remoteDir: String,
        name: String,
        open: () -> InputStream
    ): String = withContext(Dispatchers.IO) {
        val repository = if (fingerprint.isBlank()) CapturingHostKeys() else PinnedHostKeys(fingerprint)
        val session = connect(target, repository)
        try {
            val home = runCommand(session, "echo \$HOME").trim().lineSequence().firstOrNull().orEmpty()
                .ifBlank { "/root" }
            val dir = remoteDir.trim().ifBlank { "$home/opencode-uploads" }
            runCommand(session, "mkdir -p ${shellQuote(dir)}")
            val safeName = name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .trim('.', '_')
                .ifBlank { "file" }
                .takeLast(120)
            val path = "$dir/$safeName"
            val channel = session.openChannel("sftp") as ChannelSftp
            try {
                channel.connect(CONNECT_TIMEOUT_MS)
                open().use { input -> channel.put(input, path) }
            } finally {
                channel.disconnect()
            }
            path
        } finally {
            session.disconnect()
        }
    }

    /** Важные переменные окружения сервера (только для разрешения `{env:…}`). */
    suspend fun readEnv(target: SshTarget, configPath: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val session = connect(target, CapturingHostKeys())
            try {
                val names = mutableListOf<String>()
                if (configPath.isNotBlank()) {
                    val content = runCommand(session, "cat ${shellQuote(configPath)} 2>/dev/null || true")
                    Regex("""\{env:([A-Za-z_][A-Za-z0-9_]*)\}""")
                        .findAll(content)
                        .forEach { names += it.groupValues[1] }
                }
                // Переменные из server.env, где лежит OPENCODE_PASSWORD.
                names += "OPENCODE_PASSWORD"
                val unique = names.distinct().filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
                if (unique.isEmpty()) return@withContext emptyMap<String, String>()
                val script = buildString {
                    append("set +e\n")
                    unique.forEach { name ->
                        append("if [ -f /etc/opencode/server.env ]; then ")
                        append("v=\\$(grep -m1 '^").append(name).append("=' /etc/opencode/server.env | cut -d= -f2-); ")
                        append("[ -n \\\"\\\$v\\\" ] && printf '%s=%s\\\\n' '").append(name).append("' \\\"\\\$v\\\"; ")
                        append("fi\n")
                        append("v=\\$(printenv ").append(name).append(" 2>/dev/null); ")
                        append("[ -n \\\"\\\$v\\\" ] && printf '%s=%s\\\\n' '").append(name).append("' \\\"\\\$v\\\" || true\n")
                    }
                }
                val output = runCommand(session, script)
                output.lineSequence().mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
                }.toMap()
            } catch (e: Exception) {
                emptyMap()
            } finally {
                session.disconnect()
            }
        }

    // ------------------------------------------------------------------
    // SSH
    // ------------------------------------------------------------------

    /** Выполняет команду на сервере и возвращает stdout+stderr. */
    suspend fun exec(
        target: SshTarget,
        fingerprint: String = "",
        command: String
    ): String = withContext(Dispatchers.IO) {
        val repository = if (fingerprint.isBlank()) CapturingHostKeys() else PinnedHostKeys(fingerprint)
        val session = connect(target, repository)
        try {
            runCommand(session, command)
        } finally {
            session.disconnect()
        }
    }

    fun connect(target: SshTarget, repository: HostKeyRepository): Session {
        val jsch = JSch()
        if (target.privateKey.isNotBlank()) {
            val passphrase = target.keyPassphrase.takeIf { it.isNotBlank() }?.toByteArray()
            jsch.addIdentity("opencode-key", target.privateKey.toByteArray(), null, passphrase)
        }
        val session = jsch.getSession(target.user, target.host, target.port)
        if (target.password.isNotBlank()) session.setPassword(target.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig(
            "PreferredAuthentications",
            if (target.privateKey.isNotBlank()) "publickey,keyboard-interactive,password"
            else "password,keyboard-interactive"
        )
        session.hostKeyRepository = repository
        session.setServerAliveInterval(20_000)
        session.setServerAliveCountMax(6)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            throw IllegalStateException(describe(e, target), e)
        }
        return session
    }

    fun describe(e: Exception, target: SshTarget): String {
        val text = e.message.orEmpty()
        return when {
            text.contains("HostKey has been changed", ignoreCase = true) ->
                "Отпечаток ключа сервера изменился. Проверьте сервер и отпечаток заново."

            text.contains("Auth fail", ignoreCase = true) ||
                text.contains("auth cancel", ignoreCase = true) ->
                "Не удалось войти: проверьте пароль или приватный SSH-ключ."

            text.contains("Connection refused", ignoreCase = true) ->
                "Порт ${target.port} закрыт или SSH не запущен."

            text.contains("timeout", ignoreCase = true) ->
                "Сервер ${target.host} не ответил за ${CONNECT_TIMEOUT_MS / 1000} секунд."

            text.contains("UnknownHost", ignoreCase = true) ->
                "Не удалось найти сервер ${target.host}."

            else -> text.ifBlank { "Не удалось подключиться по SSH" }
        }
    }

    fun runCommand(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setInputStream(null)
        val err = ByteArrayOutputStream()
        channel.setErrStream(err)
        val input = channel.inputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        val out = input.bufferedReader(Charsets.UTF_8).readText()
        while (!channel.isClosed) Thread.sleep(40)
        channel.disconnect()
        return out + err.toString("UTF-8")
    }

    private suspend fun execStreaming(
        session: Session,
        command: String,
        onLine: suspend (String) -> Unit
    ): Int = coroutineScope {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setPty(true)
        channel.setInputStream(null)
        val input = channel.inputStream
        channel.connect(CONNECT_TIMEOUT_MS)

        val lines = Channel<String>(Channel.UNLIMITED)
        val reader = launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { buffered ->
                    while (true) {
                        val line = buffered.readLine() ?: break
                        lines.send(line)
                    }
                }
            } finally {
                lines.close()
            }
        }

        for (line in lines) {
            val clean = cleanLine(line)
            if (clean.isNotBlank()) onLine(clean)
        }
        reader.join()
        while (!channel.isClosed) delay(60)
        val status = channel.exitStatus
        channel.disconnect()
        status
    }

    // ------------------------------------------------------------------
    // Ключи хоста
    // ------------------------------------------------------------------

    private class CapturingHostKeys : HostKeyRepository {
        @Volatile
        var key: ByteArray? = null

        override fun check(host: String?, key: ByteArray?): Int {
            this.key = key
            return HostKeyRepository.OK
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "opencode-mobile-capture"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private class PinnedHostKeys(private val fingerprint: String) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.CHANGED
            return if (sha256(key) == fingerprint) HostKeyRepository.OK
            else HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "opencode-mobile-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    fun sha256(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    private fun cleanLine(line: String): String =
        line.replace(ANSI, "").replace("\r", "").trimEnd()

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Проброс фиксированного локального порта на сервер. Нужен для OAuth:
     * браузер на телефоне открывает `http://127.0.0.1:<port>/callback`, а запрос
     * уходит на тот же порт сервера, где OpenCode ждёт ответ провайдера.
     */
    class LocalBridge(
        private val target: SshTarget,
        private val fingerprint: String,
        private val localPort: Int,
        private val remotePort: Int
    ) {
        private var session: Session? = null

        @Synchronized
        fun start() {
            if (session?.isConnected == true) return
            close()
            val repository = if (fingerprint.isBlank()) CapturingHostKeys() else PinnedHostKeys(fingerprint)
            val connected = connect(target, repository)
            try {
                connected.setPortForwardingL("127.0.0.1", localPort, "127.0.0.1", remotePort)
                session = connected
            } catch (e: Exception) {
                connected.disconnect()
                throw e
            }
        }

        @Synchronized
        fun close() {
            runCatching { session?.disconnect() }
            session = null
        }
    }

    /** SSH-туннель к локальному порту OpenCode на сервере. */
    class Tunnel(
        private val target: SshTarget,
        private val fingerprint: String,
        private val remotePort: Int
    ) {
        private var session: Session? = null
        private var port: Int = 0

        @Synchronized
        fun ensurePort(): Int {
            val current = session
            if (current != null && current.isConnected && port > 0) return port

            close()
            val repository = if (fingerprint.isBlank()) {
                // Первое подключение без сохранённого отпечатка: принимаем ключ.
                object : HostKeyRepository {
                    override fun check(host: String?, key: ByteArray?): Int = HostKeyRepository.OK
                    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
                    override fun remove(host: String?, type: String?) = Unit
                    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                    override fun getKnownHostsRepositoryID(): String = "opencode-mobile-tunnel"
                    override fun getHostKey(): Array<HostKey> = emptyArray()
                    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
                }
            } else {
                PinnedHostKeys(fingerprint)
            }
            val connected = connect(target, repository)
            try {
                val parsed = connected
                    .setPortForwardingL("127.0.0.1", 0, "127.0.0.1", remotePort)
                    .takeIf { it > 0 }
                    ?: throw IllegalStateException("SSH не смог открыть локальный порт")
                session = connected
                port = parsed
                return parsed
            } catch (e: Exception) {
                connected.disconnect()
                throw e
            }
        }

        @Synchronized
        fun close() {
            runCatching { session?.disconnect() }
            session = null
            port = 0
        }
    }
}
