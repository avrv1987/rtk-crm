[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env.local')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFilePath = [System.IO.Path]::GetFullPath($EnvFile)
$allowedSids = @(
    [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value,
    'S-1-5-18',
    'S-1-5-32-544'
)
$targets = @(
    $envFilePath,
    (Join-Path $projectRoot '.demo-identities.yml'),
    (Join-Path $projectRoot 'infra\moodle\.env.local')
)

$failed = $false
$checked = 0
foreach ($path in $targets) {
    if (-not (Test-Path -LiteralPath $path)) {
        continue
    }
    $checked++
    $acl = Get-Acl -LiteralPath $path
    if (-not $acl.AreAccessRulesProtected) {
        Write-Output "${path}: наследование прав не отключено"
        $failed = $true
        continue
    }
    $unexpected = @()
    foreach ($rule in $acl.Access) {
        $sid = $rule.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value
        if ($allowedSids -notcontains $sid) {
            $unexpected += "$($rule.IdentityReference) ($($rule.FileSystemRights))"
        }
    }
    if ($unexpected.Count -gt 0) {
        Write-Output "${path}: лишний доступ для $($unexpected -join ', ')"
        $failed = $true
        continue
    }
    Write-Output ($path + ': OK')
}

if ($checked -eq 0) {
    Write-Output 'Ни один из проверяемых файлов не найден'
}
if ($failed) {
    exit 1
}
