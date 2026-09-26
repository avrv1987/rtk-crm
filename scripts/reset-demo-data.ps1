[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env.local')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFilePath = [System.IO.Path]::GetFullPath($EnvFile)
$identityFile = Join-Path $projectRoot '.demo-identities.yml'

if (-not (Test-Path -LiteralPath $envFilePath)) {
    throw "$envFilePath not found"
}
if (-not (Test-Path -LiteralPath $identityFile)) {
    throw "$identityFile not found; run scripts/bootstrap-demo.ps1 first"
}
$values = @{}
foreach ($line in Get-Content -LiteralPath $envFilePath) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $values[$matches[1]] = $matches[2]
    }
}
if ($values['DEMO_DATA'] -eq 'false' -or (Select-String -LiteralPath $identityFile -Pattern '^    demo-data: false' -Quiet)) {
    throw 'Reset is available only on a demo stand (DEMO_DATA=true)'
}

$moodleFiles = @()
if ($values['MOODLE_BASE_URL'] -eq 'http://moodle:8080') {
    $separator = if ($env:COMPOSE_PATH_SEPARATOR) { $env:COMPOSE_PATH_SEPARATOR } else { [System.IO.Path]::PathSeparator }
    $composeFiles = if ($env:COMPOSE_FILE) { $env:COMPOSE_FILE } elseif ($values['COMPOSE_FILE']) { $values['COMPOSE_FILE'] } else { 'compose.yaml' }
    $files = @($composeFiles.Split($separator)) + 'infra/moodle/compose.crm.yaml'
    if (-not [string]::IsNullOrWhiteSpace($values['MOODLE_PUBLIC_HOST'])) {
        $files += 'infra/moodle/compose.crm.prod.yaml'
    }
    foreach ($file in $files) {
        $moodleFiles += @('-f', $file)
    }
}

Push-Location $projectRoot
try {
    & docker compose --env-file $envFilePath @moodleFiles --profile demo-bootstrap run --rm -e APP_DEMOBOOTSTRAP_RESET=true backend-bootstrap
    if ($LASTEXITCODE -ne 0) {
        throw 'CRM demo data reset failed'
    }
    & docker compose --env-file $envFilePath exec -T backend sh -c 'find $APP_ATTACHMENTS_STORAGE_ROOT ${APP_REPORTS_STORAGE_ROOT:-/var/lib/rtk-crm/reports} -mindepth 1 -delete'
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot clean the attachment and report storage'
    }
    Write-Output 'Demo data reset completed: CRM data, attachments and report files match a fresh bootstrap; Keycloak accounts and passwords were not changed'
}
finally {
    Pop-Location
}
