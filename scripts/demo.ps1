#!/usr/bin/env pwsh
#Requires -Version 7.0
<#
.SYNOPSIS
  Starts the local reliable-push stack for demos and manual testing and opens the web console. Local only.
  PowerShell 7 twin of scripts/demo.sh. NOTE: not run on Windows (written and reviewed against scripts/demo.sh only).

.DESCRIPTION
  scripts\demo.ps1               # fast clock: 1 product "minute" = 1 second
  scripts\demo.ps1 -Realtime     # real clock: 1 minute = 60 seconds   (also: --realtime)

  Same stack as scripts/e2e.ps1 in the compose project reliable-push-demo with the Spring profile `demo`
  (harness/compose/demo.override.yml). It keeps running; stop it with
    docker compose -p reliable-push-demo -f harness/compose/docker-compose.yml -f harness/compose/demo.override.yml down -v
  Console: http://127.0.0.1:8090, backend API: http://127.0.0.1:8080. Generated files: harness/build/demo/.
  Environment: DEMO_READY_TIMEOUT (seconds to wait for readiness, default 120), DEMO_NO_OPEN=1 (do not open a browser).
#>
param(
    [switch] $Realtime,
    [Parameter(ValueFromRemainingArguments = $true)] [string[]] $Rest = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

foreach ($a in $Rest) {
    if ($a -eq '--realtime') { $Realtime = $true } else { throw "unknown argument: $a" }
}

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ComposeFile = Join-Path $Root 'harness/compose/docker-compose.yml'
$Override = Join-Path $Root 'harness/compose/demo.override.yml'
$RealtimeOverride = Join-Path $Root 'harness/compose/demo.realtime.override.yml'
$Project = 'reliable-push-demo'
$Out = Join-Path $Root 'harness/build/demo'
$ApiUrl = 'http://127.0.0.1:8080'
$HarnessUrl = 'http://127.0.0.1:8090'
$StopCmd = "docker compose -p $Project -f harness/compose/docker-compose.yml -f harness/compose/demo.override.yml down -v"

$ReadyTimeout = if ($env:DEMO_READY_TIMEOUT) { $env:DEMO_READY_TIMEOUT } else { '120' }
if ($ReadyTimeout -notmatch '^[0-9]+$') { throw "DEMO_READY_TIMEOUT must be a whole number of seconds, got '$ReadyTimeout'" }
$ReadyTimeout = [int]$ReadyTimeout

function Log([string] $m) { Write-Host "[demo] $m" }
function Die([string] $m) { throw [System.InvalidOperationException]::new($m) }

function Compose {
    if ($IsLinux -or $IsMacOS) {
        $env:E2E_UID = (& id -u); $env:E2E_GID = (& id -g)
    }
    $files = @('-f', $ComposeFile, '-f', $Override)
    if ($Realtime) { $files += @('-f', $RealtimeOverride) }
    & docker compose -p $Project @files @args
}

function Test-PortInUse([int] $port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $port)
        return ($task.Wait(1000) -and $client.Connected)
    } catch { return $false } finally { $client.Dispose() }
}

function Test-HttpOk([string] $Url) {
    try { return (Invoke-WebRequest -Uri $Url -TimeoutSec 2 -SkipHttpErrorCheck).StatusCode -eq 200 } catch { return $false }
}

function Get-ServiceState([string] $Service) {
    foreach ($l in @(Compose ps -a --format '{{.Service}} {{.State}}' 2>$null)) {
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

# the stack is removed again when the start fails; otherwise it keeps running
function Stop-Failed([string] $Message) {
    try { Compose logs --no-color --tail 40 | Write-Host } catch { }
    try { Compose down -v --remove-orphans *> $null } catch { }
    Die "$Message (the stack was removed again; logs of the failed start are above)"
}

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

$logDir = Join-Path $Out 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Log 'building images (backend, harness); output in harness/build/demo/logs/build.log'
Compose build *> (Join-Path $logDir 'build.log')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $logDir 'build.log') -Tail 40 | Write-Host; Die 'image build failed (harness/build/demo/logs/build.log)' }
Log 'generating per-run secrets'
Compose run --rm -q secrets *> (Join-Path $logDir 'secrets.log')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $logDir 'secrets.log') | Write-Host; Die 'secret generation failed' }
if ($Realtime) {
    # real clock: the backend must not run the fast clock
    $secrets = Join-Path $Out 'secrets.env'
    Set-Content -Path $secrets -Value @(Get-Content $secrets | Where-Object { $_ -notmatch '^RELIABLEPUSH_TESTING_MINUTEDURATION=' }) -Encoding utf8NoBOM
}

Log "starting the stack ($(if ($Realtime) { 'real clock: 1 minute = 60 s' } else { 'fast clock: 1 minute = 1 s' }))"
Compose up -d *> (Join-Path $logDir 'up.log')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $logDir 'up.log') | Write-Host; Stop-Failed 'docker compose up failed' }

$deadline = (Get-Date).AddSeconds($ReadyTimeout)
$pending = @('api', 'worker-a', 'worker-b', 'harness')
while ($true) {
    $still = @()
    foreach ($svc in $pending) {
        $state = Get-ServiceState $svc
        if ($state -ne 'running') { Stop-Failed "container $svc is not running (state: $(if ($state) { $state } else { 'none' }))" }
        if (-not (Test-Ready $svc)) { $still += $svc }
    }
    $pending = $still
    if ($pending.Count -eq 0) { break }
    if ((Get-Date) -ge $deadline) { Stop-Failed "container(s) not ready after ${ReadyTimeout}s: $($pending -join ' ')" }
    Start-Sleep -Seconds 1
}

Log "stack ready: console $HarnessUrl, API $ApiUrl"
Log "stop it with: $StopCmd"
if ($env:DEMO_NO_OPEN -ne '1') {
    try {
        if ($IsWindows) { Start-Process $HarnessUrl }
        elseif ($IsMacOS -and (Get-Command open -ErrorAction SilentlyContinue)) { & open $HarnessUrl }
        elseif (Get-Command xdg-open -ErrorAction SilentlyContinue) { & xdg-open $HarnessUrl *> $null }
        else { Log "open $HarnessUrl in a browser" }
    } catch { Log "open $HarnessUrl in a browser" }
}
