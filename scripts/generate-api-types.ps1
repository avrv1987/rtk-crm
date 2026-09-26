[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location (Join-Path $projectRoot 'frontend')
try {
    & npm run generate:api
    if ($LASTEXITCODE -ne 0) {
        throw 'OpenAPI type generation failed'
    }
}
finally {
    Pop-Location
}
