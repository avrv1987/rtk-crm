[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Invoke-Step([string]$Name, [scriptblock]$Action) {
    Write-Output "release-gate: $Name"
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed"
    }
}

Push-Location $projectRoot
try {
    Invoke-Step 'backend: mvn -q -o test' { & (Join-Path $projectRoot 'backend\mvnw.cmd') -q -o test }

    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        Invoke-Step 'frontend: npm run build' { & npm run build }
        Invoke-Step 'frontend: npm test' { & npm test }
    }
    finally {
        Pop-Location
    }

    $tmpEnv = [System.IO.Path]::GetTempFileName()
    try {
        @(
            'CRM_DB_NAME=x',
            'CRM_DB_USER=x',
            'CRM_DB_PASSWORD=x',
            'KEYCLOAK_DB_NAME=x',
            'KEYCLOAK_DB_USER=x',
            'KEYCLOAK_DB_PASSWORD=x',
            'KEYCLOAK_ADMIN_USERNAME=x',
            'KEYCLOAK_ADMIN_PASSWORD=x',
            'POSTGRES_SUPERUSER=x',
            'POSTGRES_SUPERUSER_PASSWORD=x',
            'CRM_OIDC_CLIENT_SECRET=x',
            'PUBLIC_ORIGIN=http://rtk.localhost:8081',
            'TLS_CERTIFICATE_FILE=NUL',
            'TLS_CERTIFICATE_KEY_FILE=NUL',
            'MOODLE_EDGE_HOST=lms.example.test'
        ) | Set-Content -LiteralPath $tmpEnv -Encoding utf8

        Invoke-Step 'docker compose config (compose.yaml)' {
            & docker compose --env-file $tmpEnv config --quiet
        }
        Invoke-Step 'docker compose config (compose.yaml + compose.prod.yaml)' {
            & docker compose --env-file $tmpEnv -f compose.yaml -f compose.prod.yaml config --quiet
        }
        Invoke-Step 'docker compose config (compose.yaml + compose.edge.yaml)' {
            & docker compose --env-file $tmpEnv -f compose.yaml -f compose.edge.yaml config --quiet
        }
    }
    finally {
        Remove-Item -LiteralPath $tmpEnv -ErrorAction SilentlyContinue
    }

    Write-Output 'release-gate: OK'
}
finally {
    Pop-Location
}
