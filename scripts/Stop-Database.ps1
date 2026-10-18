param([string]$PostgresBin = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$dataPath = Join-Path $projectRoot '.local\postgres'
if (-not (Test-Path -LiteralPath (Join-Path $dataPath 'PG_VERSION'))) { throw 'Local database is not initialized' }
if (-not $PostgresBin) {
    $majorVersion = (Get-Content -LiteralPath (Join-Path $dataPath 'PG_VERSION')).Trim()
    $PostgresBin = "C:\Program Files\PostgreSQL\$majorVersion\bin"
}
& (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath -m fast -w stop
if ($LASTEXITCODE -ne 0) { throw 'Failed to stop the local database' }
