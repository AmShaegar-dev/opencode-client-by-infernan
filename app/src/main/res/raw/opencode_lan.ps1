# OpenCode Client by inFernan
# Настройка локального сервера OpenCode для телефона.
# Запускается из opencode-lan.bat, который сам запрашивает права администратора.

$ErrorActionPreference = 'Continue'
$Port = 28894
$Password = $env:OPENCODE_LAN_PASSWORD
if ([string]::IsNullOrWhiteSpace($Password)) { $Password = 'AwgPass12345' }

try { $Host.UI.RawUI.WindowTitle = 'OpenCode: сервер для телефона' } catch { }

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "== $Text" -ForegroundColor Yellow
}

function Show-LanUrls([int]$ListenPort) {
    Write-Host ''
    Write-Host 'Данные для приложения на телефоне:' -ForegroundColor Green
    Write-Host '  Режим:  Прямой URL -> Локальная сеть / AWG'
    Write-Host '  Логин:  opencode'
    Write-Host "  Пароль: $Password"
    Write-Host '  URL:'
    $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.IPAddress -ne '0.0.0.0'
        } | Sort-Object InterfaceMetric | Select-Object -ExpandProperty IPAddress -Unique
    if (-not $addresses) {
        Write-Host '    (не удалось определить адрес — проверьте Wi-Fi или AWG)' -ForegroundColor Red
    } else {
        foreach ($address in $addresses) {
            Write-Host "    http://${address}:$ListenPort" -ForegroundColor Yellow
        }
    }
    Write-Host ''
    Write-Host 'Если телефон не в этой сети, включите AmneziaWG на ПК и телефоне' -ForegroundColor DarkGray
    Write-Host 'и возьмите адрес адаптера AWG из вывода выше.' -ForegroundColor DarkGray
}

Write-Host ''
Write-Host '  OpenCode Client by inFernan' -ForegroundColor Yellow
Write-Host '  Локальное подключение телефона к серверу OpenCode' -ForegroundColor DarkGray

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator
)
if (-not $isAdmin) {
    Write-Host ''
    Write-Host 'Нужны права администратора: закройте окно и запустите файл ещё раз,' -ForegroundColor Red
    Write-Host 'в запросе UAC нажмите «Да».' -ForegroundColor Red
    Read-Host 'Нажмите Enter, чтобы закрыть'
    exit 1
}

# 1. Ищем CLI OpenCode Desktop и включаем прослушивание локальной сети.
$cli = $null
$cliDir = Join-Path $env:APPDATA 'ai.opencode.desktop\cli'
if (Test-Path $cliDir) {
    $cli = Get-ChildItem $cliDir -Recurse -Filter 'opencode-cli.exe' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $cli) {
    $userPrograms = Join-Path $env:LOCALAPPDATA 'Programs'
    if (Test-Path $userPrograms) {
        $cli = Get-ChildItem $userPrograms -Recurse -Depth 4 -Filter 'opencode-cli.exe' `
            -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
}
$serveCommand = $null

if ($cli) {
    Write-Step 'Служба OpenCode Desktop найдена'
    Write-Host "  $cli" -ForegroundColor DarkGray
    Write-Host '  Задаю пароль и разрешаю подключения из локальной сети...'
    & $cli service set password $Password | Out-Host
    & $cli service set hostname 0.0.0.0 | Out-Host
    & $cli service restart | Out-Host
    Start-Sleep -Seconds 3
    Write-Host ''
    $status = (& $cli service status 2>&1 | Out-String).Trim()
    if ($status) { Write-Host $status }
    if ($status -match ':(\d{2,5})') { $Port = [int]$Matches[1] }
} else {
    $serveCommand = Get-Command opencode -ErrorAction SilentlyContinue
    if (-not $serveCommand) {
        Write-Host ''
        Write-Host 'OpenCode Desktop не найден, и команда opencode недоступна.' -ForegroundColor Red
        Write-Host 'Установите OpenCode Desktop или Node.js + opencode-ai:' -ForegroundColor Yellow
        Write-Host '    npm install -g opencode-ai' -ForegroundColor DarkGray
        Read-Host 'Нажмите Enter, чтобы закрыть'
        exit 1
    }
    Write-Step 'Запускаю отдельный сервер opencode serve'
    Write-Host '  Это окно должно оставаться открытым, пока телефон работает с сервером.' -ForegroundColor DarkGray
    $env:OPENCODE_PASSWORD = $Password
}

# 2. Разрешаем порт в брандмауэре Windows.
$ruleName = "OpenCode $Port"
$rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if (-not $rule) {
    Write-Step "Разрешаю порт $Port в брандмауэре"
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP `
        -LocalPort $Port -Action Allow -ErrorAction SilentlyContinue | Out-Null
}

Show-LanUrls $Port

Write-Host ''
Write-Host 'Не открывайте этот порт в интернет без HTTPS: для Wi-Fi и AWG' -ForegroundColor DarkGray
Write-Host 'достаточно локального адреса.' -ForegroundColor DarkGray

if ($cli) {
    Read-Host 'Готово. Нажмите Enter, чтобы закрыть окно'
} else {
    Write-Host ''
    Write-Host "Запускаю сервер на порту $Port. Остановить: Ctrl+C." -ForegroundColor Cyan
    & $serveCommand.Path serve --hostname 0.0.0.0 --port $Port
    Read-Host 'Сервер остановлен. Нажмите Enter, чтобы закрыть окно'
}
