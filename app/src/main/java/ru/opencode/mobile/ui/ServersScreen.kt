package ru.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.data.ServerProfile
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.Bg
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.Surface2
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted

@Composable
fun ServersScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppHeader(
                title = "OpenCode",
                subtitle = if (vm.servers.isEmpty()) "Серверы" else "Серверов: ${vm.servers.size}",
                actions = {
                    IconButton(onClick = { vm.refreshStatuses() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = TextMuted)
                    }
                    IconButton(onClick = { vm.openSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = TextMuted)
                    }
                    IconButton(onClick = { vm.openBackup(null) }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Данные", tint = TextMuted)
                    }
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 110.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (vm.servers.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Пока нет серверов",
                            text = "Добавьте сервер с OpenCode или установите OpenCode на новый сервер по SSH. " +
                                "Подключение по SSH-туннелю держит API закрытым от интернета."
                        )
                    }
                    item {
                        InstallBanner(onClick = { vm.openInstall() })
                    }
                } else {
                    items(vm.servers, key = { it.id }) { server ->
                        ServerCard(
                            vm = vm,
                            server = server,
                            onOpen = { vm.openChats(server.id) },
                            onEdit = { vm.openEditor(server.id) },
                            onProviders = { vm.openProviders(server.id) },
                            onBackup = { vm.openBackup(server.id) },
                            onDelete = { pendingDelete = server }
                        )
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                        InstallBanner(onClick = { vm.openInstall() })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { vm.openEditor(null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = Accent,
            contentColor = Color(0xFF1A1205),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Добавить сервер")
        }
    }

    pendingDelete?.let { server ->
        ConfirmDialog(
            title = "Удалить сервер?",
            text = "«${server.name}» исчезнет из приложения. Чаты останутся на сервере.",
            onConfirm = { vm.deleteServer(server) },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun ServerCard(
    vm: AppViewModel,
    server: ServerProfile,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onProviders: () -> Unit,
    onBackup: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val status = vm.statusOf(server.id)

    androidx.compose.material3.Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = Surface1,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(Color(server.color))
            )
            Column(Modifier.weight(1f).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color(server.color).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            server.name.take(1).uppercase(),
                            color = Color(server.color),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            server.name,
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${server.modeLabel} · ${server.displayHost}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusPill(status)
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
                                text = { Text("Открыть чаты", color = TextMain) },
                                onClick = { menu = false; onOpen() }
                            )
                            DropdownMenuItem(
                                text = { Text("Изменить сервер", color = TextMain) },
                                onClick = { menu = false; onEdit() }
                            )
                            DropdownMenuItem(
                                text = { Text("Провайдеры и ключи", color = TextMain) },
                                onClick = { menu = false; onProviders() }
                            )
                            DropdownMenuItem(
                                text = { Text("Импорт / экспорт", color = TextMain) },
                                onClick = { menu = false; onBackup() }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить", color = Color(0xFFF87171)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFF87171))
                                },
                                onClick = { menu = false; onDelete() }
                            )
                        }
                    }
                }
                if (status.error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status.error,
                        color = Color(0xFFF87171),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallBanner(onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Surface1,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Установить OpenCode", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "По SSH: установщик, systemd-служба и пароль API",
                    color = TextDim,
                    fontSize = 11.sp
                )
            }
            Text("→", color = Accent, fontSize = 18.sp)
        }
    }
}
