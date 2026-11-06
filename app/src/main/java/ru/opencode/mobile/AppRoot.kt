package ru.opencode.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import ru.opencode.mobile.ui.BackupScreen
import ru.opencode.mobile.ui.ChatScreen
import ru.opencode.mobile.ui.ChatsScreen
import ru.opencode.mobile.ui.GuideScreen
import ru.opencode.mobile.ui.InstallScreen
import ru.opencode.mobile.ui.ProvidersScreen
import ru.opencode.mobile.ui.ServerEditorScreen
import ru.opencode.mobile.ui.ServersScreen
import ru.opencode.mobile.ui.SettingsScreen
import ru.opencode.mobile.ui.UpdateAvailableDialog
import ru.opencode.mobile.ui.theme.Bg

@Composable
fun AppRoot(vm: AppViewModel) {
    LaunchedEffect(Unit) {
        vm.refreshStatuses()
        vm.checkUpdatesIfDue()
    }

    // Читаем screen на верхнем уровне: иначе BackHandler не пересчитывается,
    // когда меняется только содержимое Scaffold, и «Назад» закрывает приложение.
    val currentScreen = vm.screen
    BackHandler(enabled = vm.canGoBack()) {
        vm.goBack()
    }

    Scaffold(containerColor = Bg) { padding ->
        // imePadding сдвигает содержимое над клавиатурой, а consumeWindowInsets
        // не даёт учесть панель навигации дважды. Без этого при edge-to-edge
        // клавиатура перекрывает поля ввода (пароль SSH/API и т. п.).
        val modifier = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
        when (val screen = currentScreen) {
            Screen.Servers -> ServersScreen(vm, modifier)

            is Screen.Editor -> {
                val existing = screen.serverId?.let(vm::serverById)
                ServerEditorScreen(vm, existing, modifier)
            }

            is Screen.Install -> {
                val existing = screen.serverId?.let(vm::serverById)
                InstallScreen(vm, existing, modifier)
            }

            is Screen.Chats -> {
                val server = vm.serverById(screen.serverId)
                if (server == null) {
                    LaunchedEffect(screen.serverId) { vm.openServers() }
                } else {
                    ChatsScreen(vm, server, modifier)
                }
            }

            is Screen.Chat -> {
                val server = vm.serverById(screen.serverId)
                if (server == null) {
                    LaunchedEffect(screen.serverId) { vm.openServers() }
                } else {
                    ChatScreen(vm, server, modifier)
                }
            }

            is Screen.Providers -> {
                val server = vm.serverById(screen.serverId)
                if (server == null) {
                    LaunchedEffect(screen.serverId) { vm.openServers() }
                } else {
                    ProvidersScreen(vm, server, modifier)
                }
            }

            is Screen.Backup -> BackupScreen(vm, screen.serverId, modifier)

            Screen.Settings -> SettingsScreen(vm, modifier)

            Screen.Guide -> GuideScreen(vm, modifier)
        }
    }

    UpdateAvailableDialog(vm)
}
