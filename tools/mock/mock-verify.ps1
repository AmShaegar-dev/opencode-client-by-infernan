# Обкатка мок-сервера OpenCode V2 и приложения на эмуляторе.
# Запуск: powershell -NoProfile -ExecutionPolicy Bypass -File tools\mock\mock-verify.ps1
param(
    [string]$Node = "node"
)

$ErrorActionPreference = "Continue"
# Только ASCII-пути: adb и node не дружат с кириллицей в аргументах.
$dir = Join-Path $env:TEMP "oc-mock"
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item (Join-Path $PSScriptRoot "mock-opencode.js") (Join-Path $dir "mock-opencode.js") -Force

Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -like '*mock-opencode.js*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1

$mockOut = Join-Path $dir "mock.out"
$mockErr = Join-Path $dir "mock.err"
Start-Process -FilePath $Node -ArgumentList "`"$dir\mock-opencode.js`"" -WindowStyle Hidden `
    -RedirectStandardOutput $mockOut -RedirectStandardError $mockErr
Start-Sleep -Seconds 4

$fail = 0
function Check($name, $ok, $detail = "") {
    if ($ok) { Write-Output ("PASS  " + $name) }
    else { Write-Output ("FAIL  " + $name + " :: " + $detail); $script:fail = $script:fail + 1 }
}

function Call($method, $path, $body, $port = 4096) {
    $args = @("-s", "-X", $method, "-u", "opencode:testpass123", "-w", "`n%{http_code}")
    if ($body) {
        $f = [System.IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($f, $body, (New-Object Text.UTF8Encoding($false)))
        $args += @("--data-binary", "@$f")
    }
    $args += "http://127.0.0.1:$port$path"
    $out = & "C:\Windows\system32\curl.exe" @args 2>$null
    $text = ($out -join "`n")
    $idx = $text.LastIndexOf("`n")
    $code = $text.Substring($idx + 1).Trim()
    $payload = $text.Substring(0, $idx)
    return @{ Code = [int]$code; Body = $payload }
}

$r = Call "GET" "/api/info"
Check "info 200" ($r.Code -eq 200) $r.Body
Check "info version" ($r.Body -match '"version":"2.0.0-mock"') $r.Body

$bad = & "C:\Windows\system32\curl.exe" -s -o NUL -w "%{http_code}" -u opencode:wrong http://127.0.0.1:4096/api/info 2>$null
Check "401 on wrong password" ("$bad".Trim() -eq "401") "$bad"

$r = Call "GET" "/api/session?limit=100&order=desc"
Check "sessions 200" ($r.Code -eq 200) $r.Body
$json = $r.Body | ConvertFrom-Json
Check "sessions has one seeded" ($json.data.Count -eq 1) $r.Body

$r = Call "POST" "/api/session" '{"title":"Test chat","model":{"id":"free-model","providerID":"mock"}}'
Check "create session 200" ($r.Code -eq 200) $r.Body
$created = ($r.Body | ConvertFrom-Json).data
Check "created session id" ($created.id -like "ses_*") $r.Body
$sid = $created.id

$r = Call "GET" "/api/model"
$models = ($r.Body | ConvertFrom-Json).data
Check "disabled model hidden from /api/model" ($models.Count -eq 3) $r.Body
$free = $models | Where-Object { $_.id -eq "free-model" }
$paid = $models | Where-Object { $_.id -eq "paid-model" }
$legacy = $models | Where-Object { $_.id -eq "legacy-model" }
Check "free model costs zero" ($free.cost[0].input -eq 0 -and $free.cost[0].output -eq 0) $r.Body
Check "free model variants" ($free.variants.Count -eq 3) $r.Body
Check "paid model cost" ($paid.cost[0].input -eq 3) $r.Body
Check "legacy model absent while disabled" ($null -eq $legacy) $r.Body

# --- Конфигурация через HTTP API, без отдельного SSH -----------------
$configPath = "/root/.config/opencode/opencode.json"
$r = Call "GET" "/api/config"
$configEntries = @($r.Body | ConvertFrom-Json)
Check "config document returned" (
    $configEntries.Count -ge 1 -and $configEntries[0].path -eq $configPath
) $r.Body

$enabledJson = '{"$schema":"https://opencode.ai/config.json","providers":{"mock":{"models":{"legacy-model":{}}}}}'
$writePath = "/api/experimental/fs/write?path=" + [uri]::EscapeDataString($configPath)
$r = Call "POST" $writePath $enabledJson
Check "fs write 200" ($r.Code -eq 200) $r.Body
$r = Call "GET" "/api/model"
$legacyAfterEnable = ($r.Body | ConvertFrom-Json).data | Where-Object { $_.id -eq "legacy-model" }
Check "model enabled via HTTP config" (
    $null -ne $legacyAfterEnable -and $legacyAfterEnable.enabled -eq $true
) $r.Body

$disabledJson = '{"$schema":"https://opencode.ai/config.json","providers":{"mock":{"models":{"legacy-model":{"disabled":true}}}}}'
$r = Call "POST" $writePath $disabledJson
Check "fs write disabled 200" ($r.Code -eq 200) $r.Body
$r = Call "GET" "/api/model"
$legacyAfterDisable = ($r.Body | ConvertFrom-Json).data | Where-Object { $_.id -eq "legacy-model" }
Check "model disabled via HTTP config" ($null -eq $legacyAfterDisable) $r.Body

# Включение пишет disabled:false, а не удаляет поле — иначе reload не включает модель.
$r = Call "POST" $writePath $enabledJson
Check "re-enable via explicit disabled:false" ($r.Code -eq 200) $r.Body
$r = Call "GET" "/api/model"
$legacyReenabled = ($r.Body | ConvertFrom-Json).data | Where-Object { $_.id -eq "legacy-model" }
Check "model re-enabled after disable" ($null -ne $legacyReenabled) $r.Body
Call "POST" $writePath $disabledJson | Out-Null

$r = Call "POST" "/api/shell" '{"command":"cat ''/root/.config/opencode/opencode.json''"}'
$cmdId = ($r.Body | ConvertFrom-Json).data.id
Check "shell started" ($r.Code -eq 200 -and $cmdId -like "sh_*") $r.Body
$r = Call "GET" ("/api/shell/" + $cmdId + "/output")
Check "shell reads config" (
    $r.Code -eq 200 -and $r.Body -match 'legacy-model'
) $r.Body

$r = Call "POST" "/api/shell" '{"command":"df -Pk $HOME | tail -1"}'
$cmdId = ($r.Body | ConvertFrom-Json).data.id
$r = Call "GET" ("/api/shell/" + $cmdId + "/output")
Check "shell reports disk space" (
    $r.Code -eq 200 -and $r.Body -match 'FREE=10485760' -and $r.Body -match 'USED=12345'
) $r.Body

$r = Call "POST" "/api/shell" '{"command":"echo CLEARED"}'
$cmdId = ($r.Body | ConvertFrom-Json).data.id
$r = Call "GET" ("/api/shell/" + $cmdId + "/output")
Check "shell clears uploads" ($r.Code -eq 200 -and $r.Body -match 'CLEARED') $r.Body

$r = Call "GET" "/api/experimental/usage/limits"
Check "usage limits 200" (
    $r.Code -eq 200 -and ($r.Body | ConvertFrom-Json).data.go.rolling.percent -eq 42
) $r.Body

$r = Call "GET" ("/api/session/" + $sid + "/diff")
Check "session diff 200" (
    $r.Code -eq 200 -and @(($r.Body | ConvertFrom-Json).data).Count -eq 1
) $r.Body

$r = Call "POST" "/api/shell" '{"command":"find $HOME/opencode-uploads -type f"}'
$cmdId = ($r.Body | ConvertFrom-Json).data.id
$r = Call "GET" ("/api/shell/" + $cmdId + "/output")
Check "shell lists uploads" ($r.Code -eq 200 -and $r.Body -match 'photo.png') $r.Body

$r = Call "POST" "/api/shell" '{"command":"rm -f -- $HOME/opencode-uploads/old.bin"}'
$cmdId = ($r.Body | ConvertFrom-Json).data.id
$r = Call "GET" ("/api/shell/" + $cmdId + "/output")
Check "shell deletes upload" ($r.Code -eq 200) $r.Body

$r = Call "GET" "/api/provider"
Check "providers 3" ((($r.Body | ConvertFrom-Json).data).Count -eq 3) $r.Body
$r = Call "GET" "/api/integration"
$ints = ($r.Body | ConvertFrom-Json).data
Check "integrations 3" ($ints.Count -eq 3) $r.Body
Check "key method parsed" ($ints[0].methods[0].type -eq "key") $r.Body
Check "oauth method parsed" (
    @($ints[1].methods | Where-Object { $_.type -eq "oauth" }).Count -eq 1
) $r.Body
Check "no accounts initially" (
    @($ints | ForEach-Object { $_.connections.Count } | Measure-Object -Sum).Sum -eq 0
) $r.Body

$r = Call "POST" "/api/integration/mock/connect/key" '{"key":"sk-test-123","label":"Mock Provider"}'
Check "connect key 200" ($r.Code -eq 200) $r.Body
$keyCredential = ($r.Body | ConvertFrom-Json).data
Check "key returns credential id" ($keyCredential.id -like "cred_*") $r.Body
$r = Call "GET" "/api/integration/mock"
$mockInfo = ($r.Body | ConvertFrom-Json).data
Check "connection stored" ($mockInfo.connections.Count -eq 1) $r.Body
Check "connection type key" ($mockInfo.connections[0].type -eq "key") $r.Body
$r = Call "POST" "/api/integration/mock/connect/key" '{"key":""}'
Check "empty key rejected" ($r.Code -eq 400) $r.Body

# --- OAuth: автоматический callback (как OpenCode Go) ---------------
$r = Call "POST" "/api/integration/opencode/connect/oauth" '{"methodID":"go","label":"Go на телефоне"}'
Check "oauth start 200" ($r.Code -eq 200) $r.Body
$auto = ($r.Body | ConvertFrom-Json).data
Check "oauth auto attempt id" ($auto.attemptID -like "att_*") $r.Body
Check "oauth auto mode" ($auto.mode -eq "auto") $r.Body
Check "oauth url has redirect_uri" ($auto.url -like "*redirect_uri=*") $auto.url
$attemptPath = "/api/integration/opencode/connect/oauth/" + $auto.attemptID
$r = Call "GET" $attemptPath
Check "oauth status pending" ((($r.Body | ConvertFrom-Json).data).status -eq "pending") $r.Body
Start-Sleep -Seconds 3
$r = Call "GET" $attemptPath
Check "oauth auto completes" ((($r.Body | ConvertFrom-Json).data).status -eq "complete") $r.Body
$r = Call "GET" "/api/integration/opencode"
Check "oauth account stored" ((($r.Body | ConvertFrom-Json).data).connections.Count -eq 1) $r.Body

# --- OAuth: ручной код (как Claude Pro) -----------------------------
$r = Call "POST" "/api/integration/anthropic/connect/oauth" '{"methodID":"claude"}'
$manual = ($r.Body | ConvertFrom-Json).data
Check "oauth code mode" ($manual.mode -eq "code") $r.Body
$manualPath = "/api/integration/anthropic/connect/oauth/" + $manual.attemptID
$r = Call "POST" ($manualPath + "/complete") '{"code":"000000"}'
Check "wrong oauth code rejected" ($r.Code -eq 400) $r.Body
$r = Call "GET" $manualPath
Check "wrong code marks failed" ((($r.Body | ConvertFrom-Json).data).status -eq "failed") $r.Body

$r = Call "POST" "/api/integration/anthropic/connect/oauth" '{"methodID":"claude"}'
$manual = ($r.Body | ConvertFrom-Json).data
$manualPath = "/api/integration/anthropic/connect/oauth/" + $manual.attemptID
$r = Call "POST" ($manualPath + "/complete") '{"code":"123456"}'
Check "correct oauth code accepted" ($r.Code -eq 200) $r.Body
$r = Call "GET" "/api/integration/anthropic"
Check "manual oauth account stored" ((($r.Body | ConvertFrom-Json).data).connections.Count -eq 1) $r.Body

# --- OAuth: отмена попытки -----------------------------------------
$r = Call "POST" "/api/integration/opencode/connect/oauth" '{"methodID":"go"}'
$cancelAttempt = ($r.Body | ConvertFrom-Json).data
$cancelPath = "/api/integration/opencode/connect/oauth/" + $cancelAttempt.attemptID
$r = Call "DELETE" $cancelPath
Check "oauth cancel 204" ($r.Code -eq 204) $r.Body
$r = Call "GET" $cancelPath
Check "cancelled attempt gone" ($r.Code -eq 404) $r.Body

# --- Удаление аккаунта ---------------------------------------------
$r = Call "DELETE" ("/api/credential/" + $keyCredential.id)
Check "delete credential 204" ($r.Code -eq 204) $r.Body
$r = Call "GET" "/api/integration/mock"
Check "account removed" ((($r.Body | ConvertFrom-Json).data).connections.Count -eq 0) $r.Body

$r = Call "GET" "/api/command"
Check "commands listed" (@(($r.Body | ConvertFrom-Json).data).Count -eq 2) $r.Body
$r = Call "GET" "/api/agent"
Check "agents listed" (@(($r.Body | ConvertFrom-Json).data).Count -eq 2) $r.Body
$r = Call "POST" ("/api/session/" + $sid + "/agent") '{"agent":"plan"}'
Check "switch agent 204" ($r.Code -eq 204) $r.Body
$r = Call "POST" ("/api/session/" + $sid + "/command") '{"name":"compact","text":""}'
Check "run command 204" ($r.Code -eq 204) $r.Body

$promptPath = "/api/session/" + $sid + "/prompt"
$messagePath = "/api/session/" + $sid + "/message?limit=200&order=desc"
$r = Call "POST" $promptPath '{"text":"Echo check"}'
Check "prompt 200" ($r.Code -eq 200) $r.Body

$r = Call "GET" $messagePath
$all = @(($r.Body | ConvertFrom-Json).data)
Check "messages desc order" ($all[0].type -eq "assistant" -and $all[-1].type -eq "user") (($all | ForEach-Object { $_.type }) -join ",")
Check "assistant not completed yet" ($null -eq $all[0].time.completed) ($all[0].time | ConvertTo-Json -Compress)
$assistantText = (($all[0].content | Where-Object { $_.type -eq "text" })[0].text)
Check "assistant echoes prompt" ($assistantText -like "*Echo check") $assistantText
Check "reasoning present" (@($all[0].content | Where-Object { $_.type -eq "reasoning" }).Count -eq 1) $r.Body
Check "tool present" (@($all[0].content | Where-Object { $_.type -eq "tool" }).Count -eq 1) $r.Body

Start-Sleep -Seconds 3
$r = Call "GET" $messagePath
$done = @(($r.Body | ConvertFrom-Json).data)
Check "assistant completed after delay" ($null -ne $done[0].time.completed) ($done[0].time | ConvertTo-Json -Compress)

$r = Call "POST" ("/api/session/" + $sid + "/model") '{"model":{"id":"paid-model","providerID":"mock"}}'
Check "switch model 204" ($r.Code -eq 204) $r.Body
$r = Call "GET" "/api/session?limit=100&order=desc"
$s = ($r.Body | ConvertFrom-Json).data | Where-Object { $_.id -eq $sid }
Check "model switched" ($s.model.id -eq "paid-model") $r.Body

$r = Call "PATCH" ("/api/session/" + $sid) '{"title":"Renamed chat"}'
Check "rename 204" ($r.Code -eq 204) $r.Body
$r = Call "GET" "/api/session?limit=100&order=desc&search=Renamed"
$found = ($r.Body | ConvertFrom-Json).data
Check "search by title" ($found.Count -eq 1) $r.Body

$r = Call "GET" ("/api/experimental/session/" + $sid + "/export?sanitize=false")
$data = ($r.Body | ConvertFrom-Json).data
Check "export has info and messages" ($data.info.id -eq $sid -and $data.messages.Count -ge 2) ($r.Body.Substring(0, [Math]::Min(200, $r.Body.Length)))
$payload = $data | ConvertTo-Json -Depth 30 -Compress
$r = Call "POST" "/api/experimental/session/import" $payload
Check "import 200" ($r.Code -eq 200) $r.Body
$r = Call "GET" "/api/session?limit=100&order=desc"
$allSessions = ($r.Body | ConvertFrom-Json).data
Check "imported session present" (@($allSessions | Where-Object { $_.title -like "*import*" }).Count -eq 1) $r.Body

$r = Call "DELETE" ("/api/session/" + $sid)
Check "delete 204" ($r.Code -eq 204) $r.Body
$r = Call "GET" ("/api/session/" + $sid + "/message")
Check "deleted session 404" ($r.Code -eq 404) $r.Body

Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -like '*mock-opencode.js*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Write-Output ""
if ($fail -eq 0) { Write-Output "ALL MOCK API CHECKS PASSED" } else { Write-Output "FAILURES: $fail"; exit 1 }
