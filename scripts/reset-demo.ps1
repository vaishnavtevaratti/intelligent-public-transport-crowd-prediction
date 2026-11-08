# Restores the Kolhapur demo network to its shipped definition (routes D1-D8 with stops and road geometry, and the
# demo fleet BUS-101..BUS-110 with capacity, seats, status and route). Missing demo routes/buses are re-created.
# NOT touched: user accounts, sensors and their keys, predictions, camera counts, admin-created routes and buses.
#
#   powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1     # the backend must not be running
#   powershell -ExecutionPolicy Bypass -File scripts\reset-demo.ps1
#
# A database backup is taken first (db-backups\). Undo: restore that backup (see README "Backups").
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) {
    throw 'The backend is running on port 8080. Stop the demo first: scripts\stop-demo.ps1'
}
$jar = Join-Path $root 'backend\target\transport-backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw 'Backend jar not found; build it first: cd backend; mvn -DskipTests package' }

& (Join-Path $PSScriptRoot 'backup-db.ps1')

Write-Host 'Resetting the demo network...'
Push-Location (Join-Path $root 'backend')
try {
    $env:SIMULATION_ENABLED = 'false'
    $env:SIMULATE_PASSENGERS = 'false'
    # one-shot run: no web server, demo reset, then exit
    $output = & java -jar $jar --spring.main.web-application-type=none --app.seed.reset-demo-network=true `
        --app.seed.exit-after-seed=true 2>&1 | Out-String
    $code = $LASTEXITCODE
} finally {
    Remove-Item Env:SIMULATION_ENABLED, Env:SIMULATE_PASSENGERS -ErrorAction SilentlyContinue
    Pop-Location
}
$summary = ($output -split "`r?`n") | Where-Object { $_ -match 'Demo network reset|Converted|Seeded|ERROR|Caused by' }
$summary | ForEach-Object { Write-Host ($_ -replace '^.*?: ', '') }
if ($code -ne 0 -or -not ($summary -match 'Demo network reset')) {
    throw "Reset failed (exit code $code). Nothing was committed if the error happened before the summary line."
}
Write-Host 'Done. Start the demo again: scripts\start-demo.ps1' -ForegroundColor Green
