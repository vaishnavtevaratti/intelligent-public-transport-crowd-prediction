# Starts the whole system for a demonstration, each service in its own window:
#   ML service :8001, backend :8080, counter agent :8002 (localhost only), frontend :5173.
# Each window is supervised: if its service crashes it is restarted after 5 s (SRS 3.6.2 automatic restart).
# Close a window (or press Ctrl+C in it) to stop that service on purpose; scripts\stop-demo.ps1 stops all.
#
#   powershell -ExecutionPolicy Bypass -File scripts\start-demo.ps1
#   options: -NoBackup (skip the database backup), -NoAgent (no counter agent), -NoBrowser,
#            -Dev (Vite dev server with hot reload instead of the production build)
#
# Prerequisites (once): MySQL running; backend\application-local.properties; ml-service\.venv and
# vision-service\.venv created; npm install (see README "First-time setup").
param([switch]$NoBackup, [switch]$NoAgent, [switch]$NoBrowser, [switch]$Dev)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pidDir = Join-Path $root '.demo-pids'
New-Item -ItemType Directory -Force $pidDir | Out-Null

function Test-Port([int]$port) {
    [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

function Wait-Http([string]$name, [string]$url, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 $url | Out-Null
            Write-Host "  $name is up ($url)" -ForegroundColor Green
            return
        } catch {
            # 401/403 still means the service answered
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -lt 500) {
                Write-Host "  $name is up ($url)" -ForegroundColor Green
                return
            }
        }
        Start-Sleep -Milliseconds 700
    }
    throw "$name did not start within $seconds s - look at its window for the error"
}

# Opens a window that runs $command in $dir and restarts it when it exits unexpectedly.
function Start-Supervised([string]$title, [string]$dir, [string]$command) {
    $pidFile = Join-Path $pidDir (($title -replace '[^A-Za-z]', '') + '.pid')
    $script = @"
`$host.UI.RawUI.WindowTitle = 'OptiTransit - $title'
Set-Content -LiteralPath '$pidFile' -Value `$PID
Set-Location -LiteralPath '$dir'
while (`$true) {
    Write-Host "[`$(Get-Date -Format T)] starting $title" -ForegroundColor Cyan
    $command
    Write-Host "[`$(Get-Date -Format T)] $title exited (code `$LASTEXITCODE) - restarting in 5 s. Close this window to stop it." -ForegroundColor Yellow
    Start-Sleep -Seconds 5
}
"@
    # the window writes its PID to .demo-pids\ so stop-demo.ps1 can end it (and the service it runs)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
    Start-Process powershell -ArgumentList '-NoProfile', '-NoExit', '-EncodedCommand', $encoded | Out-Null
}

Write-Host 'OptiTransit demo start' -ForegroundColor Cyan

# 1. preflight
if (-not (Test-Port 3306)) { throw 'MySQL is not listening on port 3306. Start the MySQL80 service (services.msc or: net start MySQL80) and try again.' }
foreach ($p in 8001, 8080, 5173) {
    if (Test-Port $p) { throw "Port $p is already in use. Run scripts\stop-demo.ps1 first (or close the program using it)." }
}
$jar = Join-Path $root 'backend\target\transport-backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jar) -or (Get-Item $jar).Length -lt 10MB) {
    Write-Host 'Building the backend jar (first run)...'
    Push-Location (Join-Path $root 'backend'); try { mvn -q -DskipTests package } finally { Pop-Location }
}

# 2. database backup
if (-not $NoBackup) { & (Join-Path $PSScriptRoot 'backup-db.ps1') }

# 3. services
Start-Supervised 'ML service :8001' (Join-Path $root 'ml-service') '& .\.venv\Scripts\python.exe app.py'
Wait-Http 'ML service' 'http://127.0.0.1:8001/health' 60
Start-Supervised 'Backend :8080' (Join-Path $root 'backend') '& java -jar target\transport-backend-0.0.1-SNAPSHOT.jar'
Wait-Http 'Backend' 'http://localhost:8080/v3/api-docs' 120
if (-not $NoAgent -and -not (Test-Port 8002)) {
    Start-Supervised 'Counter agent :8002' (Join-Path $root 'vision-service') '& .\.venv\Scripts\python.exe -u agent.py'
}
if ($Dev) {
    Start-Supervised 'Frontend :5173 (dev)' $root '& npm.cmd run dev'
} else {
    # production build: fast first page load, no on-the-fly compilation during the demo
    Push-Location $root; try { npm.cmd run build --silent | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed (run npm run build to see why)' } } finally { Pop-Location }
    Start-Supervised 'Frontend :5173' $root '& npx.cmd vite preview --port 5173 --strictPort'
}
Wait-Http 'Frontend' 'http://localhost:5173/' 60

Write-Host ''
Write-Host 'All services are running:  http://localhost:5173' -ForegroundColor Green
Write-Host '  Admin     admin@transport.com / admin123   (local demo accounts)'
Write-Host '  Passenger user@transport.com / user123'
Write-Host '  Swagger   http://localhost:8080/swagger-ui.html'
Write-Host 'Stop everything with: powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1'
if (-not $NoBrowser) { Start-Process 'http://localhost:5173' }
