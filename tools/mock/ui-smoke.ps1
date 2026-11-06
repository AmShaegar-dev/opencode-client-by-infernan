# Дымовой тест UI на эмуляторе против мока OpenCode V2.
#
# 1) node tools\mock\mock-opencode.js
# 2) powershell -NoProfile -ExecutionPolicy Bypass -File tools\mock\ui-smoke.ps1 `
#        -Apk app\build\outputs\apk\debug\app-debug.apk
#
# Скрипт ставит APK, добавляет сервер (прямой URL на http://10.0.2.2:4096,
# пароль testpass123), отправляет сообщение, открывает выбор моделей,
# проверяет выключенную модель и диалог входа провайдера.
# Снимки экрана сохраняются в $env:TEMP\oc-smoke.

param(
    [string]$Sdk = $env:ANDROID_HOME,
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Shots = (Join-Path $env:TEMP "oc-smoke")
)

$ErrorActionPreference = "Continue"
if (-not $Sdk) { Write-Output "Укажите -Sdk или задайте ANDROID_HOME"; exit 1 }
$apkLocal = Join-Path $env:TEMP "opencode-mobile-smoke.apk"
Copy-Item $Apk $apkLocal -Force
$adb = Join-Path $Sdk "platform-tools\adb.exe"
$uiPath = Join-Path $env:TEMP "oc-smoke-ui.xml"
New-Item -ItemType Directory -Force $Shots | Out-Null

$fail = 0
function Check($name, $ok, $detail = "") {
    if ($ok) { Write-Output ("PASS  " + $name) }
    else { Write-Output ("FAIL  " + $name + " :: " + $detail); $script:fail = $script:fail + 1 }
}
function Adb { $a = @("-e") + $args; & $adb @a }
function Shot($name) {
    Adb shell screencap -p /sdcard/sc.png | Out-Null
    Adb pull /sdcard/sc.png (Join-Path $Shots "$name.png") | Out-Null
    Write-Output ("SHOT " + $name)
}
function DumpUi {
    Adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    Adb pull /sdcard/ui.xml $uiPath | Out-Null
    return [xml](Get-Content $uiPath -Encoding UTF8)
}
function FindNode($pattern, [int]$minY = 0) {
    $xml = DumpUi
    $nodes = $xml.SelectNodes("//node[contains(@text,'$pattern') or contains(@content-desc,'$pattern')]")
    foreach ($item in $nodes) {
        if ($item.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            if ([int]$Matches[2] -ge $minY) { return $item }
        }
    }
    return $null
}
function HasText($pattern) {
    $xml = DumpUi
    return $null -ne $xml.SelectSingleNode(
        "//node[contains(@text,'$pattern') or contains(@content-desc,'$pattern')]"
    )
}
function TapText($pattern, [int]$minY = 0) {
    $node = FindNode $pattern $minY
    if (-not $node) { Write-Output "NOT FOUND: $pattern"; return $false }
    if ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
        Write-Output "TAP '$pattern' at $x,$y"
        Adb shell input tap $x $y | Out-Null
        Start-Sleep -Milliseconds 1200
        return $true
    }
    return $false
}
function TapRightOf($pattern, [int]$x = 1000) {
    $node = FindNode $pattern
    if (-not $node -or -not ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')) {
        Write-Output "NOT FOUND: $pattern"; return $false
    }
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    Write-Output "TAP-right '$pattern' at $x,$y"
    Adb shell input tap $x $y | Out-Null
    Start-Sleep -Milliseconds 900
    return $true
}
function Tap($x, $y) { Adb shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 900 }
function TypeText($t) { Adb shell input text $t | Out-Null; Start-Sleep -Milliseconds 400 }
function SetEditField([int]$index, $text) {
    $xml = DumpUi
    $nodes = $xml.SelectNodes('//node[@class="android.widget.EditText"]')
    if ($index -ge $nodes.Count) {
        Write-Output ("EDIT NOT FOUND: " + $index)
        return
    }
    $node = $nodes[$index]
    if (-not ($node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')) { return }
    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    Tap $x $y
    Start-Sleep -Milliseconds 700
    Adb shell input keyevent 123 | Out-Null
    $keys = (1..40 | ForEach-Object { "67" }) -join " "
    Adb shell input keyevent $keys | Out-Null
    Start-Sleep -Milliseconds 300
    TypeText $text
    Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 700
}

for ($i = 0; $i -lt 80; $i++) {
    $b = (Adb shell getprop sys.boot_completed 2>$null)
    if ("$b".Trim() -eq "1" -and ((Adb shell "service check input") -join "") -match "found") { break }
    Start-Sleep -Seconds 3
}
Start-Sleep -Seconds 5
Write-Output "booted"

Adb shell settings put global window_animation_scale 0 | Out-Null
Adb shell settings put global transition_animation_scale 0 | Out-Null
Adb shell settings put global animator_duration_scale 0 | Out-Null
Adb shell am force-stop ru.opencode.mobile | Out-Null

Adb push $apkLocal /data/local/tmp/app-debug.apk | Out-Null
# Удаляем старую подпись (release/debug), иначе install -r может молча не обновиться.
Adb shell pm uninstall ru.opencode.mobile | Out-Null
Adb shell pm install -r /data/local/tmp/app-debug.apk | Out-Null
Adb shell pm clear ru.opencode.mobile | Out-Null
Adb logcat -c | Out-Null
Adb shell am start -n ru.opencode.mobile/.MainActivity | Out-Null
Start-Sleep -Seconds 5
Shot "01_home"

# --- Настройки и проверка обновлений --------------------------------
TapText "Настройки"
Start-Sleep -Seconds 1
Check "settings screen" (HasText "Проверить обновление")
TapText "Инструкция: сервер и вкладки"
Start-Sleep -Seconds 1
Check "guide screen" (HasText "компьютер и телефон")
Check "guide server step" (HasText "Запустите сервер")
TapText "Назад"
Start-Sleep -Milliseconds 700
Check "back to settings" (HasText "Проверить обновление")
TapText "Назад"
Start-Sleep -Milliseconds 700

# --- Добавление сервера (прямой URL на мок) -------------------------
Tap 945 2150
Start-Sleep -Seconds 1
TapText "Прямой URL"
Start-Sleep -Seconds 1
TapText "Инструкция: сервер и вкладки"
Start-Sleep -Seconds 1
Check "connection guide" (HasText "ai.opencode.desktop")
Check "guide server step" (HasText "Запустите сервер")
TapText "Понятно"
Start-Sleep -Milliseconds 700
SetEditField 0 "Mock"
SetEditField 1 "http://10.0.2.2:4096"
SetEditField 2 "testpass123"
TapText "Проверить подключение"
Start-Sleep -Seconds 3
Check "connection test online" (HasText "Подключено. OpenCode")
Shot "02_editor"
TapText "Добавить сервер"
Start-Sleep -Seconds 6
Shot "03_servers"
Check "server added" (HasText "Mock")
Check "storage card visible" (HasText "Хранилище сервера")
Check "disk free shown" (HasText "10.0 ГБ")
Check "usage card visible" (HasText "Лимиты OpenCode Go")
Check "usage percent shown" (HasText "42%")

# Панель хранилища и лимитов сворачивается и разворачивается.
Check "panel toggle visible" (HasText "Свернуть панель")
TapText "Свернуть панель"
Start-Sleep -Seconds 1
Check "panel collapsed" (HasText "Хранилище и лимиты")
Check "storage hidden" (-not (HasText "Хранилище сервера"))
TapText "Хранилище и лимиты"
Start-Sleep -Seconds 1
Check "storage expanded again" (HasText "Хранилище сервера")

# Список файлов на сервере.
TapText "Файлы"
Start-Sleep -Seconds 1
Check "files dialog" (HasText "Файлы на сервере")
Check "file list item" (HasText "photo.png")
TapText "Закрыть"
Start-Sleep -Milliseconds 500

# Очистка кэша файлов: диалог и подтверждение.
TapText "Очистить"
Check "clear storage dialog" (HasText "Очистить кэш файлов?")
TapText "Очистить" 1300
Start-Sleep -Seconds 3
Check "storage card survives clear" (HasText "Хранилище сервера")

TapText "Mock"
Start-Sleep -Seconds 3
Shot "04_chats"
Check "seed chat listed" (HasText "Приветственный чат")

TapText "Приветственный чат"
Start-Sleep -Seconds 3
Shot "05_chat"

# --- Отправка сообщения (клавиатуру прячем до нажатия «отправить») --
Tap 400 2250
TypeText "SmokeTest"
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Milliseconds 900
Tap 984 2256
Start-Sleep -Seconds 1
Shot "06_sending"
Start-Sleep -Seconds 6
Shot "07_answer"
Check "message sent" (HasText "SmokeTest")
Check "mock answer received" (HasText "Эхо: SmokeTest")

# Размышления скрыты по умолчанию и раскрываются нажатием.
Check "reasoning header visible" (HasText "Размышления")
Check "reasoning text hidden" (-not (HasText "Размышление mock-модели"))
TapText "Размышления"
Start-Sleep -Seconds 1
Check "reasoning expands" (HasText "Размышление mock-модели")
TapText "Скрыть размышления"
Start-Sleep -Milliseconds 700
Check "reasoning hides again" (-not (HasText "Размышление mock-модели"))

# --- Выбор моделей: включение и выключение через API сервера --------
TapText "Mock Free Model" 1500
Start-Sleep -Seconds 1
Shot "08_models"
Check "model counter" (HasText "Включено 3 из 4")
Check "disabled model listed" (HasText "выключена")
TapText "В избранное"
Start-Sleep -Milliseconds 700
Check "favorite model saved" (HasText "Избранные · 1")

# Системная «Назад» должна закрыть полноэкранный диалог, а не приложение.
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 1
Check "back closes model dialog" (HasText "Приветственный чат")
TapText "Mock Free Model" 1500
Start-Sleep -Seconds 1
Check "model dialog reopens" (HasText "Включено 3 из 4")

# Два переключения подряд: раньше параллельные записи затирали конфиг.
TapRightOf "Mock Paid Model"
TapRightOf "Mock Legacy"
Start-Sleep -Seconds 9
Shot "09_models_two_updates"
Check "no error after two updates" (-not (HasText "Не удалось"))
Check "two updates applied" (HasText "Включено 3 из 4")

# Выключенная модель исчезает из /api/model, но должна остаться в списке.
Check "disabled model still listed" (HasText "Mock Paid Model")
TapRightOf "Mock Paid Model"
Start-Sleep -Seconds 8
Shot "10_model_reenabled"
Check "model reenabled and listed" (HasText "Включено 4 из 4")

# Вернём исходное состояние для следующих прогонов.
TapRightOf "Mock Legacy"
Start-Sleep -Seconds 8
Shot "11_model_disabled"
Check "model disabled without SSH" (HasText "Включено 3 из 4")
TapText "Готово"
Start-Sleep -Milliseconds 700

# --- Уровень размышлений (варианты модели) --------------------------
TapText "Меню"
Start-Sleep -Seconds 1
Check "compact menu item" (HasText "Сжать контекст")
Check "thinking menu item" (HasText "Уровень размышлений")
TapText "Уровень размышлений"
Start-Sleep -Seconds 1
Shot "12_thinking"
Check "thinking dialog" (HasText "Сколько думать?")
TapText "Высокий"
Start-Sleep -Seconds 1
Check "thinking variant applied" (HasText "Высокий")

# --- Изменения файлов последнего ответа ------------------------------
TapText "Меню"
Start-Sleep -Seconds 1
Check "diff menu item" (HasText "Изменения файлов")
TapText "Изменения файлов"
Start-Sleep -Seconds 1
Check "diff dialog" (HasText "MainActivity.kt")
TapText "Закрыть"
Start-Sleep -Milliseconds 500

# --- Команды и агенты ------------------------------------------------
TapText "Меню"
Start-Sleep -Seconds 1
TapText "Команды"
Start-Sleep -Seconds 1
Check "commands dialog" (HasText "compact")
TapText "Закрыть"
Start-Sleep -Milliseconds 500
TapText "Меню"
Start-Sleep -Seconds 1
TapText "Агенты"
Start-Sleep -Seconds 1
Check "agents dialog" (HasText "Планирование")
TapText "Закрыть"
Start-Sleep -Milliseconds 500

# --- Провайдеры: способы входа (OAuth / ключ) ------------------------
TapText "Меню"
Start-Sleep -Seconds 1
Shot "10_menu"
Check "server upload menu item" (HasText "Загрузить файл на сервер")
TapText "Провайдеры и ключи"
Start-Sleep -Seconds 3
Shot "11_providers"
Check "oauth integration visible" (HasText "Войти через OpenCode")
TapText "OpenCode Zen" 1100
Start-Sleep -Seconds 1
Shot "12_auth_methods"
Check "oauth method in dialog" (HasText "Войти через OpenCode")
Check "key method in dialog" (HasText "API key")
TapText "Закрыть"
Start-Sleep -Milliseconds 700

# --- Системная кнопка «Назад»: провайдеры → чат → чаты → серверы ---
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 1
Shot "13_back_chat"
Check "back returns to chat" (HasText "Приветственный чат")
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 1
Shot "14_back_chats"
Check "back returns to chats" (HasText "Новый чат")
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 1
Shot "15_back_servers"
Check "back returns to servers" (HasText "Добавить сервер")

$crashes = Adb logcat -d 2>$null | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime.*ru.opencode.mobile"
if ($crashes) {
    Write-Output "CRASHES:"
    $crashes | ForEach-Object { $_.Line }
    $fail = $fail + 1
}
if ($fail -eq 0) { Write-Output "SMOKE PASSED" } else { Write-Output ("SMOKE FAILURES: " + $fail); exit 1 }
