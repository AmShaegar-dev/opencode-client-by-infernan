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
 * Пошаговая инструкция: как открыть сервер OpenCode Desktop для телефона,
 * подключиться через Wi-Fi или AWG и продолжить работу с теми же чатами.
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

    StepCard("1", "Запустите сервер OpenCode Desktop") {
        Text(
            "Если установлен OpenCode Desktop, отдельный opencode serve не нужен: " +
                "у Desktop уже есть фоновая служба. Найдите её CLI:",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        CodeBlock(
            "\$cli = Get-ChildItem \"\$env:APPDATA\\ai.opencode.desktop\\cli\" -Recurse " +
                "-Filter \"opencode-cli.exe\" |\n" +
                "  Sort-Object LastWriteTime -Descending |\n" +
                "  Select-Object -First 1 -ExpandProperty FullName\n" +
                "\$cli"
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Задайте пароль и разрешите слушать все интерфейсы. Desktop на несколько " +
                "секунд отключится и переподключится сам:",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        CodeBlock(
            "&\$cli service set password \"AwgPass12345\"\n" +
                "&\$cli service set hostname 0.0.0.0\n" +
                "&\$cli service restart\n" +
                "Start-Sleep 3\n" +
                "&\$cli service status"
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "service status покажет адрес и порт, например http://0.0.0.0:28894. " +
                "0.0.0.0 — это нормально: это адрес прослушивания, а не адрес для телефона.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Проверьте, что служба слушает все интерфейсы (порт подставьте свой):",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        CodeBlock("Get-NetTCPConnection -LocalPort 28894 -State Listen")
        Spacer(Modifier.height(8.dp))
        Text(
            "В LocalAddress должно быть 0.0.0.0 или ::, а не 127.0.0.1. " +
                "Если там 127.0.0.1 — повторите service set hostname 0.0.0.0 и restart.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
    Spacer(Modifier.height(12.dp))

    StepCard("2", "Разрешите порт в брандмауэре") {
        Text(
            "Обычно Windows сам создаёт правило, когда служба начинает слушать порт. " +
                "Проверить существующие правила:",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        CodeBlock("Get-NetFirewallRule -DisplayName \"opencode-cli.exe\"")
        Spacer(Modifier.height(8.dp))
        Text(
            "Если правила нет, откройте PowerShell от имени администратора " +
                "(иначе будет «Отказано в доступе») и выполните:",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        CodeBlock(
            "New-NetFirewallRule -DisplayName \"OpenCode Desktop 28894\" " +
                "-Direction Inbound -Protocol TCP -LocalPort 28894 -Action Allow"
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Порт подставьте из service status. Можно также разрешить opencode-cli.exe " +
                "через окно брандмауэра Windows.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
    Spacer(Modifier.height(12.dp))

    StepCard("3", "Подключите телефон") {
        Text(
            "Узнайте адрес компьютера в Wi-Fi или AWG:",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        CodeBlock(
            "Get-NetIPAddress -AddressFamily IPv4 |\n" +
                "  Where-Object { \$_.IPAddress -notlike '127.*' -and \$_.IPAddress -notlike '169.254.*' } |\n" +
                "  Select-Object IPAddress, InterfaceAlias"
        )
        Spacer(Modifier.height(8.dp))
        Bullet("Для Wi-Fi берите адрес Wi-Fi адаптера, например 192.168.3.4.")
        Spacer(Modifier.height(6.dp))
        Bullet("Для AWG берите адрес адаптера AmneziaWG, например 172.16.17.35.")
        Spacer(Modifier.height(6.dp))
        Bullet("Если в конфиге AWG стоит 172.16.17.35/32 в AllowedIPs — это может быть адрес телефона, а не компьютера. Адрес ПК смотрите в секции [Interface] его конфига.")
        Spacer(Modifier.height(10.dp))
        Text(
            "В приложении:",
            color = TextMain,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Bullet("Режим «Прямой URL» → «Локальная сеть / AWG».")
        Spacer(Modifier.height(6.dp))
        Bullet("URL: http://<IP компьютера>:28894 — например, http://192.168.3.4:28894.")
        Spacer(Modifier.height(6.dp))
        Bullet("Пароль API: AwgPass12345 — тот, что задали в service set password.")
        Spacer(Modifier.height(6.dp))
        Bullet("Нажмите «Проверить подключение» — должна появиться версия OpenCode.")
        Spacer(Modifier.height(8.dp))
        Text(
            "Если service status показывает 0.0.0.0, не вводите его в телефон. " +
                "В телефон идёт адрес компьютера из Get-NetIPAddress.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
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

    StepCard("5", "Если приложение пишет «Не удалось загрузить»") {
        Text(
            "Сначала проверьте API с компьютера. Подставьте адрес компьютера и порт:",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        CodeBlock(
            "\$p = &\$cli service get password\n" +
                "\$b = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes(\"opencode:\$p\"))\n" +
                "Invoke-WebRequest \"http://192.168.3.4:28894/api/info\" " +
                "-Headers @{Authorization=\"Basic \$b\"}"
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "200 — сервер и пароль верные. 401 — неверный пароль. Таймаут или отказ — " +
                "неверный IP/порт или брандмауэр.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        Bullet("В URL стоит 0.0.0.0 или старый порт — исправьте профиль сервера в приложении.")
        Spacer(Modifier.height(6.dp))
        Bullet("Get-NetTCPConnection показывает 127.0.0.1 — снова выполните service set hostname 0.0.0.0 и service restart.")
        Spacer(Modifier.height(6.dp))
        Bullet("Телефон не в той же Wi-Fi и AWG не подключён — включите AWG на ПК и телефоне, используйте адрес ПК в AWG.")
        Spacer(Modifier.height(6.dp))
        Bullet("Порт не 28894 — возьмите точный порт из service status и укажите его в приложении.")
        Spacer(Modifier.height(8.dp))
        Text(
            "Не открывайте порт напрямую в интернет без HTTPS: пароль и переписка " +
                "будут передаваться открыто. Для AWG и локальной сети это допустимо, " +
                "потому что трафик идёт внутри туннеля.",
            color = Red,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
    Spacer(Modifier.height(12.dp))

    StepCard("6", "Если Desktop не установлен") {
        Text(
            "Запустите отдельный сервер OpenCode. Пароль задаёте сами:",
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
            "В приложении: http://<IP компьютера>:4096 и пароль AwgPass12345. " +
                "Если команда opencode не найдена, используйте полный путь к opencode-cli.exe.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
    Spacer(Modifier.height(12.dp))

    StepCard("7", "Закройте доступ обратно") {
        Text(
            "Когда телефон больше не нужен, верните службу Desktop на localhost. " +
                "Команды используют \$cli из шага 1:",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        CodeBlock(
            "&\$cli service set hostname 127.0.0.1\n" +
                "&\$cli service restart\n" +
                "Start-Sleep 3\n" +
                "&\$cli service status"
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "status снова покажет http://127.0.0.1:28894 — телефон подключиться уже не сможет.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        Bullet("Если создавали правило брандмауэра, удалите его в PowerShell от имени администратора: Remove-NetFirewallRule -DisplayName \"OpenCode Desktop 28894\".")
        Spacer(Modifier.height(6.dp))
        Bullet("Если запускали отдельный сервер opencode serve, нажмите Ctrl+C в его окне и при необходимости удалите правило \"OpenCode 4096\".")
        Spacer(Modifier.height(6.dp))
        Bullet("Проверьте, что порт больше не слушается: Get-NetTCPConnection -LocalPort 28894 -State Listen. Пустой вывод — порт закрыт.")
        Spacer(Modifier.height(6.dp))
        Bullet("Если AWG был нужен только для телефона, отключите его на телефоне и на ПК.")
        Spacer(Modifier.height(8.dp))
        Text(
            "Пароль службы можно оставить или сменить командой service set password. " +
                "После закрытия порта приложение на телефоне перестанет загружать чаты — " +
                "это ожидаемо.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }

    if (showActions) {
        Spacer(Modifier.height(14.dp))
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
