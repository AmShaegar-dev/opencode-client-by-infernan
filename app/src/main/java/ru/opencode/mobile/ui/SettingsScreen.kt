package ru.opencode.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ru.opencode.mobile.AppViewModel
import ru.opencode.mobile.BuildConfig
import ru.opencode.mobile.data.UpdateChecker
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.Surface2
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun installApk(context: Context, file: java.io.File) {
    if (!file.exists() || file.length() == 0L) {
        Toast.makeText(context, "Файл обновления не найден", Toast.LENGTH_LONG).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Не удалось открыть установщик: ${e.message ?: "разрешите установку из этого приложения"}",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun UpdateAvailableDialog(vm: AppViewModel) {
    val context = LocalContext.current
    val prompt = vm.updatePrompt ?: return
    val state = vm.update
    val downloading = state.downloading
    val downloaded = state.downloaded

    AlertDialog(
        onDismissRequest = { if (!downloading) vm.dismissUpdatePrompt() },
        title = {
            Text(
                "Доступно обновление ${prompt.version}",
                color = TextMain,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                Text("OpenCode Client by inFernan", color = TextDim, fontSize = 11.sp)
                if (prompt.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        prompt.notes.take(3000),
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Accent
                        )
                        Text(
                            "Скачиваем APK" +
                                (state.progress?.let { " · ${(it * 100).toInt()}%" } ?: "…"),
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
                if (state.error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.error, color = Red, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (downloaded != null) {
                        installApk(context, downloaded)
                    } else if (prompt.assetUrl.isBlank()) {
                        openUrl(context, prompt.pageUrl)
                    } else {
                        vm.downloadUpdate { result ->
                            result.onSuccess { installApk(context, it) }
                        }
                    }
                }
            ) {
                Text(
                    when {
                        downloaded != null -> "Установить"
                        prompt.assetUrl.isBlank() -> "Открыть GitHub"
                        else -> "Скачать и установить"
                    },
                    color = Accent
                )
            }
        },
        dismissButton = {
            Row {
                if (prompt.pageUrl.isNotBlank()) {
                    TextButton(onClick = { openUrl(context, prompt.pageUrl) }) {
                        Text("GitHub", color = TextMuted)
                    }
                }
                TextButton(
                    enabled = !downloading,
                    onClick = { vm.dismissUpdatePrompt() }
                ) {
                    Text("Позже", color = TextMuted)
                }
            }
        },
        containerColor = Surface2
    )
}

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = vm.update
    val lastChecked = remember(state.lastChecked) {
        state.lastChecked.takeIf { it > 0 }?.let {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(it))
        }
    }

    Column(modifier.fillMaxSize()) {
        AppHeader(
            title = "Настройки",
            subtitle = "OpenCode Client by inFernan",
            onBack = { vm.openServers() }
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionCard {
                Text("Приложение", color = TextMain, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                SettingLine("Версия", BuildConfig.VERSION_NAME)
                SettingLine("Репозиторий", UpdateChecker.PROJECT_URL)
            }
            Spacer(Modifier.height(10.dp))

            SectionCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "Обновления",
                        color = TextMain,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.checking) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Accent
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (state.downloading) {
                    Text(
                        "Скачиваем обновление" +
                            (state.progress?.let { " · ${(it * 100).toInt()}%" } ?: "…"),
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                when {
                    state.error.isNotBlank() -> Text(
                        state.error,
                        color = Red,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    state.latest != null -> {
                        Text(
                            "Доступна версия ${state.latest.version}",
                            color = Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (state.latest.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                state.latest.notes.take(2500),
                                color = TextMuted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }

                    lastChecked != null -> Text(
                        "Установлена последняя версия. Проверено: $lastChecked",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    else -> Text(
                        "Проверка ещё не выполнялась.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = if (state.checking) "Проверяем…" else "Проверить обновление",
                        onClick = { vm.checkUpdates() },
                        enabled = !state.checking && !state.downloading,
                        modifier = Modifier.weight(1f)
                    )
                    state.latest?.let { update ->
                        PrimaryButton(
                            text = if (state.downloaded != null) "Установить" else "Обновить",
                            onClick = {
                                val downloaded = state.downloaded
                                if (downloaded != null) {
                                    installApk(context, downloaded)
                                } else if (update.assetUrl.isBlank()) {
                                    openUrl(context, update.pageUrl)
                                } else {
                                    vm.downloadUpdate { result ->
                                        result.onSuccess { installApk(context, it) }
                                    }
                                }
                            },
                            enabled = !state.downloading,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                state.latest?.takeIf { it.assetUrl.isBlank() }?.let { update ->
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { openUrl(context, update.pageUrl) }) {
                        Text("Открыть релиз на GitHub", color = Accent, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Проверка обновлений выполняется не чаще одного раза в сутки. " +
                    "Перед установкой Android попросит разрешить установку из этого приложения.",
                color = TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextMuted, fontSize = 11.sp)
    }
}

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
    }
}
