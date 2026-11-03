package ru.opencode.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.opencode.mobile.data.ServerStatus
import ru.opencode.mobile.ui.theme.Accent
import ru.opencode.mobile.ui.theme.Bg
import ru.opencode.mobile.ui.theme.Border
import ru.opencode.mobile.ui.theme.Green
import ru.opencode.mobile.ui.theme.Red
import ru.opencode.mobile.ui.theme.Surface1
import ru.opencode.mobile.ui.theme.Surface2
import ru.opencode.mobile.ui.theme.TextDim
import ru.opencode.mobile.ui.theme.TextMain
import ru.opencode.mobile.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = TextMain)
                }
            }
        },
        actions = actions,
        // Отступ статус-бара уже даёт Scaffold; иначе он считается дважды
        // и сверху остаётся большая пустая полоса.
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Bg,
            titleContentColor = TextMain,
            actionIconContentColor = TextMuted,
            navigationIconContentColor = TextMain
        )
    )
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontSize = 13.sp, color = TextMuted, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { if (placeholder.isNotBlank()) Text(placeholder, color = TextDim) },
            enabled = enabled,
            singleLine = singleLine,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (secret && !visible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
            trailingIcon = if (secret) {
                {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "Скрыть" else "Показать",
                            tint = TextMuted
                        )
                    }
                }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
                focusedContainerColor = Surface1,
                unfocusedContainerColor = Surface1,
                cursorColor = Accent
            )
        )
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 11.sp, color = TextDim, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(50.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(12.dp),
        color = if (enabled && !loading) Accent else Surface2,
        contentColor = if (enabled && !loading) Color(0xFF1A1205) else TextDim
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF1A1205)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = Surface1,
        contentColor = TextMain,
        border = BorderStroke(1.dp, Border)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    padding: Int = 16,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Surface1,
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(padding.dp)) { content() }
    }
}

@Composable
fun StatusPill(status: ServerStatus) {
    val color = when {
        status.checking -> TextMuted
        status.online -> Green
        status.error.isNotBlank() -> Red
        else -> TextDim
    }
    val text = when {
        status.checking -> "проверка…"
        status.online -> "online ${status.version}".trim()
        status.error.isNotBlank() -> "offline"
        else -> "не проверен"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyState(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "Удалить",
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextMain, fontWeight = FontWeight.SemiBold) },
        text = { Text(text, color = TextMuted) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (destructive) Red else Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextMuted) }
        },
        containerColor = Surface2,
        titleContentColor = TextMain,
        textContentColor = TextMuted
    )
}

@Composable
fun LoadingLine(text: String = "Загрузка…") {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0E0E11),
        border = BorderStroke(1.dp, Border)
    ) {
        Text(
            text,
            modifier = Modifier.padding(10.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = TextMuted
        )
    }
}

fun relativeTime(seconds: Long): String {
    if (seconds <= 0) return ""
    val now = System.currentTimeMillis()
    val millis = if (seconds < 10_000_000_000L) seconds * 1000 else seconds
    val diff = (now - millis) / 1000
    return when {
        diff < 60 -> "только что"
        diff < 3600 -> "${diff / 60} мин назад"
        diff < 86400 -> "${diff / 3600} ч назад"
        diff < 86400 * 7 -> "${diff / 86400} дн назад"
        else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(millis))
    }
}

@Composable
fun ClickableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
