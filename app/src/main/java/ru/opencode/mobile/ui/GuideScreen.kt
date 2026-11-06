package ru.opencode.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.AccentSoft
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface2
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

/**
 * Пошаговая инструкция: как поднять OpenCode на компьютере, подключить
 * телефон через AWG/локальную сеть и продолжить работу с теми же чатами.
 */
@Composable
fun GuideScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        AppHeader(
            title = "Инструкция",
            subtitle = "Сервер на ПК и работа с телефона",
            onBack = { vm.goBack() }
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            GuideContent(vm, showActions = true)
        }
    }
}

/** Та же инструкция в диалоге: редактор сервера остаётся заполненным. */
@Composable
fun GuideDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Инструкция", color = TextMain, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                GuideContent(vm, showActions = false)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Понятно", color = Accent)
            }
        },
        containerColor = Surface2
    )
}

@Composable
private fun GuideContent(vm: AppViewModel, showActions: Boolean) {
            SectionCard {
                Text(
                    "Один сервер — компьютер и телефон",
                    color = TextMain,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Телефон подключается к тому же серверу OpenCode, что и компьютер. " +
                        "Чаты, история, модели и провайдеры хранятся на сервере, поэтому " +
                        "в телефоне открываются те же сессии, что и в Desktop.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "«Вкладки» в Desktop — это открытые сессии. На телефоне те же сессии " +
                        "находятся в разделе «Чаты».",
                    color = Accent,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(12.dp))

            StepCard("1", "Запустите сервер на компьютере") {
                Text(
                    "Сервер должен слушать не только 127.0.0.1, но и адрес, по которому " +
                        "его видит телефон. В Windows откройте PowerShell:",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                CodeBlock(
                    "\$env:OPENCODE_PASSWORD=\"AwgPass12345\"\n" +
                        "opencode serve --hostname 0.0.0.0 --port 4096"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "0.0.0.0 — это адрес прослушивания на ПК. В телефон вводите адрес " +
                        "компьютера в AWG, например 172.16.17.35.",
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Если команда opencode не найдена, найдите встроенный CLI Desktop:",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                CodeBlock(
                    "Get-ChildItem \"\$env:APPDATA\\OpenCode\\cli\" -Recurse " +
                        "-Filter \"opencode-cli.exe\" |\n" +
                        "  Sort-Object LastWriteTime -Descending |\n" +
                        "  Select-Object -First 1 -ExpandProperty FullName"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Затем запустите найденный файл с теми же аргументами: " +
                        "serve --hostname 0.0.0.0 --port 4096.",
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Можно использовать и службу Desktop, не запуская второй сервер:",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                CodeBlock(
                    "opencode service set hostname 0.0.0.0\n" +
                        "opencode service status\n" +
                        "opencode service get password"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "status покажет адрес и порт, get password — пароль API. " +
                        "У службы Desktop порт может отличаться от 4096.",
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(12.dp))

            StepCard("2", "Разрешите порт в брандмауэре") {
                Text(
                    "Windows может блокировать входящие подключения. В PowerShell " +
                        "от имени администратора:",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                CodeBlock(
                    "New-NetFirewallRule -DisplayName \"OpenCode 4096\" " +
                        "-Direction Inbound -Protocol TCP -LocalPort 4096 -Action Allow"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Если используете порт службы Desktop, укажите его вместо 4096. " +
                        "На Linux обычно достаточно: sudo ufw allow 4096/tcp.",
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(12.dp))

            StepCard("3", "Подключите телефон") {
                Bullet("Включите AmneziaWG на телефоне и убедитесь, что компьютер доступен по его адресу в AWG (например, 172.16.17.35).")
                Spacer(Modifier.height(6.dp))
                Bullet("В приложении выберите «Прямой URL» → «Локальная сеть / AWG».")
                Spacer(Modifier.height(6.dp))
                Bullet("URL: http://<IP ПК в AWG>:4096 — например, http://172.16.17.35:4096.")
                Spacer(Modifier.height(6.dp))
                Bullet("Пароль API: тот, что задан в OPENCODE_PASSWORD или получен командой service get password.")
                Spacer(Modifier.height(6.dp))
                Bullet("Нажмите «Проверить подключение» — должна появиться версия OpenCode.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Для адресов 10.x.x.x, 172.16–31.x.x и 192.168.x.x HTTP разрешён: " +
                        "это приватные сети. Для внешнего адреса используйте HTTPS или SSH-туннель.",
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(12.dp))

            StepCard("4", "Работайте с теми же вкладками") {
                Text(
                    "Сессии хранятся на сервере, а не в памяти телефона. Поэтому:",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                Bullet("Откройте «Чаты» — там будут те же чаты, что и в Desktop.")
                Spacer(Modifier.height(6.dp))
                Bullet("Открыли чат на телефоне — продолжаете с того же места. Ответ и вызовы инструментов приходят в реальном времени.")
                Spacer(Modifier.height(6.dp))
                Bullet("Новый чат, созданный на телефоне, появится на компьютере в списке сессий. Если Desktop не обновил список сразу, откройте его заново.")
                Spacer(Modifier.height(6.dp))
                Bullet("Можно держать Desktop и телефон открытыми одновременно: они работают с одним сервером и одной историей.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Телефон открывает те же сессии, но не переключает сами вкладки окна Desktop. " +
                        "Для продолжения работы достаточно открыть нужный чат в «Чатах».",
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(12.dp))

            StepCard("5", "Если не подключается") {
                Bullet("Проверьте, что AWG включён на телефоне, а в AllowedIPs есть подсеть AWG (например, 172.16.17.0/24) или 0.0.0.0/0.")
                Spacer(Modifier.height(6.dp))
                Bullet("На ПК проверьте, что сервер слушает все интерфейсы: Get-NetTCPConnection -LocalPort 4096 -State Listen. В LocalAddress должно быть 0.0.0.0 или ::, а не 127.0.0.1.")
                Spacer(Modifier.height(6.dp))
                Bullet("Откройте в браузере телефона http://<IP ПК в AWG>:4096/api/info. Если страница не открывается — это сеть или брандмауэр, а не пароль.")
                Spacer(Modifier.height(6.dp))
                Bullet("Если браузер просит логин или возвращает JSON, а приложение пишет 401 — проверьте пароль API.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Не открывайте порт 4096 напрямую в интернет без HTTPS: пароль и переписка " +
                        "будут передаваться открыто. Для AWG и локальной сети это допустимо, " +
                        "потому что трафик идёт внутри туннеля.",
                    color = Red,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(14.dp))

            if (showActions) {
                SecondaryButton(
                    text = "Добавить сервер",
                    onClick = { vm.openEditor(null) }
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    text = "К списку серверов",
                    onClick = { vm.openServers() }
                )
            }
            Spacer(Modifier.height(24.dp))
}

@Composable
private fun StepCard(number: String, title: String, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = AccentSoft) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Text(
                        number,
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                color = TextMain,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", color = Accent, fontSize = 13.sp)
        Text(text, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun CodeBlock(code: String) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Surface2,
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                code,
                color = TextMain,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = { copyText(context, code) },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Копировать", color = Accent, fontSize = 12.sp)
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("OpenCode", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}
