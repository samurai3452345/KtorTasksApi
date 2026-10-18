param([string]$PostgresBin = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$dataPath = Join-Path $projectRoot '.local\postgres'
$localPath = Join-Path $projectRoot '.local'

if (-not $PostgresBin) {
    $install = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+$' } |
        Sort-Object { [int]$_.Name } -Descending | Select-Object -First 1
    if (-not $install) { throw 'PostgreSQL not found. Install PostgreSQL 17 or use docker compose up -d.' }
    $PostgresBin = Join-Path $install.FullName 'bin'
}
$pgCtl = Join-Path $PostgresBin 'pg_ctl.exe'
$initDb = Join-Path $PostgresBin 'initdb.exe'
$psql = Join-Path $PostgresBin 'psql.exe'
foreach ($binary in @($pgCtl, $initDb, $psql)) {
    if (-not (Test-Path -LiteralPath $binary)) { throw "Not found: $binary" }
}
New-Item -ItemType Directory -Path $localPath -Force | Out-Null

if (-not (Test-Path -LiteralPath (Join-Path $dataPath 'PG_VERSION'))) {
    $passwordFile = Join-Path $localPath 'initdb-password.tmp'
    try {
        [IO.File]::WriteAllText($passwordFile, 'tasks_local_password', [Text.UTF8Encoding]::new($false))
        & $initDb -D $dataPath -U tasks_app --encoding=UTF8 --locale=C --auth=scram-sha-256 "--pwfile=$passwordFile"
        if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
    } finally {
        if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile }
    }
    Add-Content -LiteralPath (Join-Path $dataPath 'postgresql.conf') -Value "`nlisten_addresses = '127.0.0.1'`nport = 55434`n"
}

& $pgCtl -D $dataPath status *> $null
if ($LASTEXITCODE -ne 0) {
    # pg_ctl redirects the database output to a log and waits until PostgreSQL is ready.
    $startArguments = @('-D', ('"' + $dataPath + '"'), '-l', ('"' + (Join-Path $localPath 'postgres.log') + '"'), '-w', 'start')
    $start = Start-Process -FilePath $pgCtl -ArgumentList $startArguments -WindowStyle Hidden -PassThru
    # -Wait waits for the entire process tree, including the background database.
    $start.WaitForExit()
    if ($start.ExitCode -ne 0) { throw 'PostgreSQL failed to start. See .local/postgres.log; port 55434 may be busy.' }
}

$oldPassword = $env:PGPASSWORD
$oldTimeout = $env:PGCONNECT_TIMEOUT
try {
    $env:PGPASSWORD = 'tasks_local_password'
    $env:PGCONNECT_TIMEOUT = '5'
    $exists = & $psql -h 127.0.0.1 -p 55434 -U tasks_app -d postgres -w -tAc "SELECT 1 FROM pg_database WHERE datname = 'ktor_tasks'"
    if ($LASTEXITCODE -ne 0) { throw 'Database connection failed' }
    if ($exists -ne '1') {
        & $psql -h 127.0.0.1 -p 55434 -U tasks_app -d postgres -w -v ON_ERROR_STOP=1 -c 'CREATE DATABASE ktor_tasks;'
        if ($LASTEXITCODE -ne 0) { throw 'CREATE DATABASE failed' }
    }
} finally {
    $env:PGPASSWORD = $oldPassword
    $env:PGCONNECT_TIMEOUT = $oldTimeout
}
Write-Output 'PostgreSQL is ready: localhost:55434 / ktor_tasks (user: tasks_app)'
