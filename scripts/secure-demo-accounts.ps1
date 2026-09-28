[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env.local'),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Keep = @()
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFilePath = [System.IO.Path]::GetFullPath($EnvFile)
$accountsFile = Join-Path $projectRoot '.demo-accounts.local'
$demoAccounts = @('kam-a', 'kam-b', 'kam-c', 'kam-d', 'leader', 'leader-b', 'admin', 'management', 'enrol', 'partner', 'unprofiled', 'unprofiled-2')

if (-not (Test-Path -LiteralPath $envFilePath)) {
    throw "$envFilePath not found"
}
foreach ($account in $Keep) {
    if ($demoAccounts -cnotcontains $account) {
        throw "$account is not a demo account ($($demoAccounts -join ' '))"
    }
}
$envLines = @(Get-Content -LiteralPath $envFilePath)
if (($envLines | Where-Object { $_ -match '^DEMO_DATA=' } | Select-Object -Last 1) -eq 'DEMO_DATA=false') {
    throw 'The installation has no demo accounts (DEMO_DATA=false)'
}

function New-Secret {
    $bytes = [byte[]]::new(24)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Invoke-Compose([string[]]$Arguments, [string]$InputText) {
    if ($PSBoundParameters.ContainsKey('InputText')) {
        $output = $InputText | & docker compose --env-file $envFilePath @Arguments
    }
    else {
        $output = & docker compose --env-file $envFilePath @Arguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments[0..3] -join ' ') failed"
    }
    return $output
}

function Invoke-Keycloak([string[]]$Arguments) {
    return Invoke-Compose (@('exec', '-T', 'keycloak', '/opt/keycloak/bin/kcadm.sh') + $Arguments)
}

function Protect-File([string]$Path) {
    if ($IsWindows) {
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $Path /inheritance:r /grant:r "*${sid}:(F)" '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' | Out-Null
    }
    else {
        & chmod 600 $Path
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot restrict access to $Path"
    }
}

Push-Location $projectRoot
$passwordsFile = $null
$completed = $false
try {
    Invoke-Compose @('exec', '-T', 'keycloak', 'bash', '-c', '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"') | Out-Null
    $enabled = @()
    $disabled = @()
    $passwordsFile = Join-Path $projectRoot ".demo-accounts.$([System.IO.Path]::GetRandomFileName())"
    New-Item -ItemType File -Path $passwordsFile | Out-Null
    Protect-File $passwordsFile
    foreach ($account in $demoAccounts) {
        $users = @(Invoke-Keycloak @('get', 'users', '-r', 'rtk-crm', '-q', "username=$account", '-q', 'exact=true') | ConvertFrom-Json)
        if ($users.Count -eq 0) {
            continue
        }
        if ($users.Count -ne 1) {
            throw "Keycloak user $account is not unique"
        }
        $id = $users[0].id
        $password = New-Secret
        $credential = @{ type = 'password'; value = $password; temporary = $false } | ConvertTo-Json -Compress
        Invoke-Compose @('exec', '-T', 'keycloak', '/opt/keycloak/bin/kcadm.sh', 'update', "users/$id/reset-password", '-r', 'rtk-crm', '-f', '-') $credential | Out-Null
        if ($Keep -ccontains $account) {
            Invoke-Keycloak @('update', "users/$id", '-r', 'rtk-crm', '-s', 'enabled=true') | Out-Null
            Add-Content -LiteralPath $passwordsFile -Value "$account $password" -Encoding utf8NoBOM
            $enabled += $account
        }
        else {
            Invoke-Keycloak @('update', "users/$id", '-r', 'rtk-crm', '-s', 'enabled=false') | Out-Null
            $disabled += $account
        }
        Invoke-Keycloak @('create', "users/$id/logout", '-r', 'rtk-crm', '-s', 'realm=rtk-crm', '-s', "user=$id") | Out-Null
    }
    $sessionUsers = (@($enabled + $disabled) | ForEach-Object { "'$_'" }) -join ','
    if ($sessionUsers) {
        Invoke-Compose @('exec', '-T', 'postgres', 'sh', '-c', 'psql --username="$POSTGRES_USER" --dbname="$CRM_DB_NAME" -v ON_ERROR_STOP=1 -q -c "$1"', 'sh', "DELETE FROM spring_session WHERE principal_name IN ($sessionUsers)") | Out-Null
    }
    Move-Item -LiteralPath $passwordsFile -Destination $accountsFile -Force
    $completed = $true
    if ($envLines -match '^DEMO_ACCOUNTS_SECURED=') {
        $envLines = $envLines -replace '^DEMO_ACCOUNTS_SECURED=.*$', 'DEMO_ACCOUNTS_SECURED=true'
    }
    else {
        $envLines += 'DEMO_ACCOUNTS_SECURED=true'
    }
    Set-Content -LiteralPath $envFilePath -Value $envLines -Encoding utf8NoBOM
    Protect-File $envFilePath
    $enabledText = if ($enabled) { $enabled -join ' ' } else { 'none' }
    $disabledText = if ($disabled) { $disabled -join ' ' } else { 'none' }
    Write-Output "Demo accounts secured. Enabled with individual passwords: $enabledText. Disabled: $disabledText. Passwords are in $accountsFile"
}
finally {
    Pop-Location
    if (-not $completed -and $passwordsFile -and (Test-Path -LiteralPath $passwordsFile)) {
        if ((Get-Item -LiteralPath $passwordsFile).Length -gt 0) {
            [Console]::Error.WriteLine("secure-demo-accounts: passwords already set for kept accounts are in $passwordsFile; run the script again to finish")
        }
        else {
            Remove-Item -LiteralPath $passwordsFile
            [Console]::Error.WriteLine('secure-demo-accounts: run the script again to finish')
        }
    }
}
