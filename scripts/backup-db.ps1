# Backs up the MySQL database transport_db to db-backups\transport_db_auto_<timestamp>.sql
# (SRS 3.6.2: basic backup mechanism). Read-only for the database; nothing is deleted from it.
#
#   powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1            # one backup now
#   powershell -ExecutionPolicy Bypass -File scripts\backup-db.ps1 -Keep 20   # keep the 20 newest automatic backups
#
# The password comes from the DB_PASSWORD environment variable or backend\application-local.properties
# (git-ignored). It is passed to mysqldump through MYSQL_PWD, never on the command line, and is never printed.
# Restore:  mysql -u root -p transport_db < db-backups\transport_db_auto_<timestamp>.sql
param(
    [int]$Keep = 10,
    [string]$Database = 'transport_db',
    [string]$User = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' })
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'db-backups'
New-Item -ItemType Directory -Force $outDir | Out-Null

$dump = (Get-Command mysqldump.exe -ErrorAction SilentlyContinue).Source
if (-not $dump) {
    $dump = Get-ChildItem 'C:\Program Files\MySQL\*\bin\mysqldump.exe' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $dump) { throw 'mysqldump.exe not found (install MySQL Server or add its bin folder to PATH)' }

$password = $env:DB_PASSWORD
if (-not $password) {
    $local = Join-Path $root 'backend\application-local.properties'
    if (Test-Path $local) {
        $line = Get-Content $local | Where-Object { $_ -match '^DB_PASSWORD=' } | Select-Object -First 1
        if ($line) { $password = $line.Substring('DB_PASSWORD='.Length).Trim() }
    }
}
if (-not $password) { throw 'Database password not found: set DB_PASSWORD or backend\application-local.properties' }

$file = Join-Path $outDir ("{0}_auto_{1}.sql" -f $Database, (Get-Date -Format 'yyyyMMdd_HHmmss'))
$env:MYSQL_PWD = $password
try {
    # --single-transaction: consistent snapshot without locking the running application
    & $dump -u $User --single-transaction --routines --no-tablespaces --result-file="$file" $Database 2>&1 |
        Where-Object { $_ -notmatch 'Using a password|MYSQL_PWD' }
    if ($LASTEXITCODE -ne 0) { throw "mysqldump failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
$size = [math]::Round((Get-Item $file).Length / 1KB)
Write-Host "Backup written: $file ($size KB)"

# keep only the newest $Keep automatic backups (manual backups in the folder are never touched)
Get-ChildItem $outDir -Filter "${Database}_auto_*.sql" | Sort-Object Name -Descending |
    Select-Object -Skip $Keep | ForEach-Object { Remove-Item $_.FullName; Write-Host "Removed old automatic backup $($_.Name)" }
