package ru.opencode.mobile

import android.content.Context
import android.util.Base64
import ru.opencode.mobile.R

/**
 * Готовый установщик для Windows: `.bat`, который сам запрашивает права
 * администратора и запускает PowerShell-скрипт из `res/raw/opencode_lan.ps1`.
 *
 * PowerShell-код передаётся в base64 (по частям, чтобы не упереться в лимит
 * длины строки cmd), поэтому в самом .bat нет ни кириллицы, ни спецсимволов —
 * файл одинаково работает в любой кодовой странице Windows.
 */
object LanSetup {

    const val FILE_NAME = "opencode-lan.bat"

    fun buildBat(context: Context): String {
        val script = context.resources.openRawResource(R.raw.opencode_lan)
            .use { it.readBytes() }
        val encoded = Base64.encodeToString(script, Base64.NO_WRAP)

        return buildString {
            append("@echo off\r\n")
            append("setlocal\r\n")
            append("title OpenCode: local server\r\n")
            append("set \"B64FILE=%TEMP%\\opencode-lan.b64\"\r\n")
            append("set \"PS1=%TEMP%\\opencode-lan.ps1\"\r\n")
            append("if exist \"%B64FILE%\" del /q \"%B64FILE%\" >nul 2>&1\r\n")
            encoded.chunked(500).forEach { part ->
                append(">>\"%B64FILE%\" echo ").append(part).append("\r\n")
            }
            append(
                "powershell -NoProfile -ExecutionPolicy Bypass -Command " +
                    "\"\$b=[IO.File]::ReadAllText('%B64FILE%');" +
                    "[IO.File]::WriteAllText('%PS1%',[Text.Encoding]::UTF8.GetString(" +
                    "[Convert]::FromBase64String(\$b)),(New-Object Text.UTF8Encoding(\$true)))\"\r\n"
            )
            append("if errorlevel 1 goto fail\r\n")
            append(
                "powershell -NoProfile -ExecutionPolicy Bypass -Command " +
                    "\"Start-Process powershell -Verb RunAs -ArgumentList @(" +
                    "'-NoExit','-NoProfile','-ExecutionPolicy','Bypass','-File','%PS1%')\"\r\n"
            )
            append("exit /b 0\r\n")
            append(":fail\r\n")
            append("echo Failed to prepare the setup script.\r\n")
            append("pause\r\n")
        }
    }
}
