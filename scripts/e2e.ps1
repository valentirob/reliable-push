#!/usr/bin/env pwsh
#Requires -Version 7.0
<#
.SYNOPSIS
  End-to-end scenarios of reliable-push on the local PC (never in CI). PowerShell 7 twin of scripts/e2e.sh.

.DESCRIPTION
  scripts\e2e.ps1                     # whole catalogue (internal scenarios, then the worker-crash ones)
  scripts\e2e.ps1 -Scenario <name>    # one scenario   (also: --scenario <name>)
  scripts\e2e.ps1 -Keep               # leave the stack running (console: http://127.0.0.1:8090)   (also: --keep)

  Stack: harness/compose/docker-compose.yml (PostgreSQL, api, worker-a, worker-b, harness), per-run secrets from the
  one-shot `secrets` service. Outputs in harness/build/e2e/: summary.json, junit/e2e.xml, logs/<service>.log and the
  backend JSON log files. Exit code 0 only if every scenario PASSED.
  Environment: E2E_READY_TIMEOUT (seconds to wait for readiness, default 120).
#>
param(
    [string] $Scenario = '',
    [switch] $Keep,
    [Parameter(ValueFromRemainingArguments = $true)] [string[]] $Rest = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

# accept the bash-style flags too
for ($i = 0; $i -lt $Rest.Count; $i++) {
    switch -Regex ($Rest[$i]) {
        '^--scenario$' { if ($i + 1 -ge $Rest.Count) { throw '--scenario needs a name' }; $Scenario = $Rest[++$i] }
        '^--scenario=' { $Scenario = $Rest[$i].Substring(11) }
        '^--keep$' { $Keep = $true }
        default { throw "unknown argument: $($Rest[$i])" }
    }
}

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ComposeFile = Join-Path $Root 'harness/compose/docker-compose.yml'
$Project = 'reliable-push-e2e'
$Out = Join-Path $Root 'harness/build/e2e'
$ApiUrl = 'http://127.0.0.1:8080'
$HarnessUrl = 'http://127.0.0.1:8090'

$ReadyTimeout = if ($env:E2E_READY_TIMEOUT) { $env:E2E_READY_TIMEOUT } else { '120' }
if ($ReadyTimeout -notmatch '^[0-9]+$') { throw "E2E_READY_TIMEOUT must be a whole number of seconds, got '$ReadyTimeout'" }
$ReadyTimeout = [int]$ReadyTimeout

function Log([string] $m) { Write-Host "[e2e] $m" }
function Warn([string] $m) { Write-Host "[e2e] WARNING: $m" -ForegroundColor Yellow }
function Die([string] $m) { throw [System.InvalidOperationException]::new($m) }

# docker compose on the E2E project. Linux/macOS: generated files belong to the invoking user; Windows: not needed.
function Compose {
    if ($IsLinux -or $IsMacOS) {
        $env:E2E_UID = (& id -u); $env:E2E_GID = (& id -g)
    }
    & docker compose -p $Project -f $ComposeFile @args
}

function Test-PortInUse([int] $port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $port)
        return ($task.Wait(1000) -and $client.Connected)
    } catch { return $false } finally { $client.Dispose() }
}

# Returns @{ Code = <int, 0 = no answer>; Body = <string> } and writes the body to $OutFile when given
function Invoke-Http([string] $Method, [string] $Url, [string] $OutFile = $null, [string] $Body = $null, [int] $Max = 30) {
    $params = @{ Method = $Method; Uri = $Url; TimeoutSec = $Max; SkipHttpErrorCheck = $true }
    if ($Body) { $params.Body = $Body; $params.ContentType = 'application/json' }
    try {
        $r = Invoke-WebRequest @params
        $text = if ($r.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
        if ($OutFile) { Set-Content -Path $OutFile -Value $text -NoNewline -Encoding utf8NoBOM }
        return @{ Code = [int]$r.StatusCode; Body = $text }
    } catch {
        return @{ Code = 0; Body = '' }
    }
}

function Test-HttpOk([string] $Url) { (Invoke-Http GET $Url $null $null 2).Code -eq 200 }

function Get-ServiceState([string] $Service) {
    $lines = Compose ps -a --format '{{.Service}} {{.State}}' 2>$null
    foreach ($l in @($lines)) {
        $p = "$l".Split(' ')
        if ($p.Count -ge 2 -and $p[0] -eq $Service) { return $p[1] }
    }
    return ''
}

function Test-Ready([string] $Service) {
    switch ($Service) {
        'api' { return Test-HttpOk "$ApiUrl/actuator/health/readiness" }
        'harness' { return Test-HttpOk "$HarnessUrl/actuator/health/readiness" }
        default {
            Compose exec -T $Service wget -q -T 2 -O /dev/null 'http://127.0.0.1:8080/actuator/health/readiness' *> $null
            return $LASTEXITCODE -eq 0
        }
    }
}

function Save-Logs {
    $logDir = Join-Path $Out 'logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    foreach ($svc in @(Compose config --services 2>$null)) {
        if ($svc) { Compose logs --no-color --timestamps $svc *> (Join-Path $logDir "$svc.log") }
    }
    if ((Get-ServiceState 'harness') -eq 'running') {
        Compose cp 'harness:/tmp/rp-logs/.' "$logDir/" *> $null
    }
}

function Get-CrashDelay([string] $Name) {
    if ($Name -like '*-inside-window') { return 3 }
    if ($Name -like '*-outside-window') { return 25 }
    return $null
}

function Write-Results([string] $Json) {
    $o = $Json | ConvertFrom-Json
    $items = if ($o.PSObject.Properties.Name -contains 'results') { $o.results } else { @($o.result) }
    foreach ($r in @($items)) { if ($r) { Write-Host ("  {0}  {1} ({2} ms)" -f $r.status, $r.scenario, $r.durationMs) } }
}

function Invoke-Internal([string] $Body) {
    $r = Invoke-Http POST "$HarnessUrl/console/api/scenarios/run" (Join-Path $Out 'responses/run.json') $Body 600
    if ($r.Code -ne 200) { Write-Host $r.Body; Die "POST /console/api/scenarios/run answered $($r.Code)" }
    Write-Results $r.Body
}

function Invoke-External([string] $Name) {
    $delay = Get-CrashDelay $Name
    if ($null -eq $delay) { Die "external scenario $Name has no crash delay in scripts/e2e.ps1 (expected *-inside-window or *-outside-window)" }
    Log "${Name}: stopping worker-b (no second worker may claim the row)"
    Compose stop worker-b *> $null
    $r = Invoke-Http POST "$HarnessUrl/console/api/scenarios/$Name/prepare" (Join-Path $Out "responses/$Name-prepare.json") $null 120
    $tid = $null
    if ($r.Code -eq 200) { $tid = ($r.Body | ConvertFrom-Json).trackingId }
    if (-not $tid) {
        Warn "${Name}: prepare answered $($r.Code) without a trackingId (its result is in the summary)"
        Compose start worker-b *> $null
        return
    }
    Log "${Name}: prepared trackingId $tid; waiting for its webhook call to be in flight"
    $seen = $false
    $deadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $deadline) {
        $f = Invoke-Http GET "$HarnessUrl/console/api/inbox/in-flight?appId=shop"
        if ($f.Code -eq 200 -and @(($f.Body | ConvertFrom-Json).trackingIds) -contains $tid) { $seen = $true; break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $seen) { Warn "${Name}: the webhook call never showed up in flight within 40 s (verify will report it)" }
    Log "${Name}: killing worker-a while the call hangs; workers stay down ${delay}s"
    Compose kill worker-a *> $null
    Start-Sleep -Seconds $delay
    Compose start worker-a worker-b *> $null
    Log "${Name}: workers restarted; verifying"
    $body = @{ trackingId = $tid } | ConvertTo-Json -Compress
    $v = Invoke-Http POST "$HarnessUrl/console/api/scenarios/$Name/verify" (Join-Path $Out "responses/$Name-verify.json") $body 600
    if ($v.Code -ne 200) { Write-Host $v.Body; Die "POST /console/api/scenarios/$Name/verify answered $($v.Code)" }
    Write-Results $v.Body
}

$stackStarted = $false
$exitCode = 1
try {
    # ---- preflight ---------------------------------------------------------------------------------------------
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Die 'docker not found: install Docker Desktop or Podman with compose' }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { Die "'docker compose version' failed: the compose v2 plugin is required and the docker daemon must run" }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { Die 'cannot reach the docker daemon (is it running?)' }
    if (@(Compose ps -a -q 2>$null | Where-Object { $_ }).Count -gt 0) {
        Log "removing the stack left by a previous run (project $Project)"
        Compose down -v --remove-orphans *> $null
    }
    foreach ($port in 8080, 8090) {
        if (Test-PortInUse $port) { Die "port $port is in use on 127.0.0.1; stop the process using it (netstat -ano | findstr :$port) and retry" }
    }
    foreach ($d in 'logs', 'junit', 'responses') { Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $Out $d) }
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $Out 'summary.json')
    foreach ($d in 'logs', 'junit', 'responses') { New-Item -ItemType Directory -Force -Path (Join-Path $Out $d) | Out-Null }

    # ---- build, secrets, start ---------------------------------------------------------------------------------
    $buildLog = Join-Path $Out 'logs/build.log'
    Log 'building images (backend, harness); output in harness/build/e2e/logs/build.log'
    Compose build *> $buildLog
    if ($LASTEXITCODE -ne 0) { Get-Content $buildLog -Tail 40 | Write-Host; Die 'image build failed (harness/build/e2e/logs/build.log)' }
    Log 'generating per-run secrets'
    Compose run --rm -q secrets *> (Join-Path $Out 'logs/secrets.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $Out 'logs/secrets.log') | Write-Host; Die 'secret generation failed' }
    Log 'starting the stack'
    $stackStarted = $true
    Compose up -d *> (Join-Path $Out 'logs/up.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $Out 'logs/up.log') | Write-Host; Die 'docker compose up failed' }

    # ---- readiness (Review Focus 1) ----------------------------------------------------------------------------
    Log "waiting up to ${ReadyTimeout}s for api, worker-a, worker-b and harness"
    $deadline = (Get-Date).AddSeconds($ReadyTimeout)
    $pending = @('api', 'worker-a', 'worker-b', 'harness')
    while ($true) {
        $still = @()
        foreach ($svc in $pending) {
            $state = Get-ServiceState $svc
            if ($state -ne 'running') {
                Compose logs --no-color --tail 60 $svc | Write-Host
                Die "container $svc is not running (state: $(if ($state) { $state } else { 'none' })); logs in harness/build/e2e/logs/$svc.log"
            }
            if (-not (Test-Ready $svc)) { $still += $svc }
        }
        $pending = $still
        if ($pending.Count -eq 0) { break }
        if ((Get-Date) -ge $deadline) {
            foreach ($svc in $pending) {
                Write-Host "`n----- last log lines of $svc -----"
                Compose logs --no-color --tail 60 $svc | Write-Host
            }
            Die "container(s) not ready after ${ReadyTimeout}s: $($pending -join ' '); logs in harness/build/e2e/logs/"
        }
        Start-Sleep -Seconds 1
    }
    Log 'stack ready'

    # ---- scenarios ---------------------------------------------------------------------------------------------
    $list = Invoke-Http GET "$HarnessUrl/console/api/scenarios" (Join-Path $Out 'responses/scenarios.json')
    if ($list.Code -ne 200) { Die "GET /console/api/scenarios answered $($list.Code)" }
    $catalogue = @($list.Body | ConvertFrom-Json)
    $external = @($catalogue | Where-Object { $_.externalControl } | ForEach-Object { $_.name })
    if ($Scenario) {
        if (-not (@($catalogue.name) -contains $Scenario)) { Die "unknown scenario '$Scenario'; known: $($catalogue.name -join ' ')" }
        $expected = 1
        if ($external -contains $Scenario) { Invoke-External $Scenario }
        else { Log "running $Scenario"; Invoke-Internal (@{ names = @($Scenario) } | ConvertTo-Json -Compress) }
    } else {
        $expected = $catalogue.Count
        Log "running the catalogue ($expected scenarios; the internal ones take a few minutes)"
        Invoke-Internal '{}'
        foreach ($name in $external) { Invoke-External $name }
    }

    # ---- reports and verdict -----------------------------------------------------------------------------------
    $s = Invoke-Http GET "$HarnessUrl/console/api/scenarios/report/summary.json" (Join-Path $Out 'summary.json')
    if ($s.Code -ne 200) { Die "GET report/summary.json answered $($s.Code)" }
    $j = Invoke-Http GET "$HarnessUrl/console/api/scenarios/report/junit.xml" (Join-Path $Out 'junit/e2e.xml')
    if ($j.Code -ne 200) { Die "GET report/junit.xml answered $($j.Code)" }
    $summary = @($s.Body | ConvertFrom-Json)
    $passed = @($summary | Where-Object { $_.status -eq 'PASSED' }).Count
    Log "summary: $passed/$($summary.Count) passed (expected $expected scenarios)"
    if ($summary.Count -ne $expected -or $passed -ne $summary.Count -or $summary.Count -eq 0) {
        Log 'failed or missing scenarios: read failure.expected / actual / hint in harness/build/e2e/summary.json'
        $exitCode = 1
    } else {
        $exitCode = 0
    }
} catch {
    Write-Host "[e2e] ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
} finally {
    if ($stackStarted) {
        Save-Logs
        if ($Keep) {
            Log "stack kept running (-Keep): console $HarnessUrl, API $ApiUrl"
            Log "stop it with: docker compose -p $Project -f harness/compose/docker-compose.yml down -v"
        } else {
            Compose down -v --remove-orphans *> $null
        }
    }
    Log "outputs: $Out (summary.json, junit/e2e.xml, logs/)"
    if ($exitCode -eq 0) { Log 'RESULT: all scenarios PASSED' } else { Log "RESULT: FAILED (exit $exitCode)" }
}
exit $exitCode
