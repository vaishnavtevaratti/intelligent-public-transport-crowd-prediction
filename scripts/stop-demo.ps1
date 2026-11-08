# Stops everything started by start-demo.ps1: first the supervisor windows (so nothing is restarted),
# then any process still listening on the demo ports. Counters started by the agent are stopped with it.
#   powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1
$pidDir = Join-Path (Split-Path -Parent $PSScriptRoot) '.demo-pids'
Get-ChildItem $pidDir -Filter '*.pid' -ErrorAction SilentlyContinue | ForEach-Object {
    $id = [int](Get-Content $_.FullName -TotalCount 1)
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -eq 'powershell') {
        taskkill /PID $id /T /F | Out-Null   # /T also ends the service it was running
        Write-Host "Stopped supervisor window $($_.BaseName) ($id)"
    }
    Remove-Item $_.FullName
}
foreach ($port in 8001, 8002, 8080, 5173) {
    Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
            if ($_ -gt 0) { taskkill /PID $_ /T /F | Out-Null; Write-Host "Stopped process $_ on port $port" }
        }
}
Write-Host 'OptiTransit demo stopped. (MySQL keeps running; the data is kept.)'
