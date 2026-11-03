package ru.opencode.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.opencode.mobile.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            OpenCodeTheme {
                val vm: AppViewModel = viewModel()
                AppRoot(vm)
            }
        }
        // Мессенджеры присылают JSON как «.txt»: сразу сохраняем
        // копию с расширением .json, чтобы её можно было выбрать вручную.
        intent?.let { handleSharedIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val shared = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        lifecycleScope.launch {
            val renamed = withContext(Dispatchers.IO) {
                SharedFiles.ensureJsonName(this@MainActivity, shared)
            }
            if (renamed != null) {
                Toast.makeText(
                    this@MainActivity,
                    "Файл чатов сохранён как JSON — импортируйте его на экране «Данные».",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
