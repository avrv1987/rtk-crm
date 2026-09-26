[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env.local')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFilePath = [System.IO.Path]::GetFullPath($EnvFile)

function New-Secret {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Read-EnvironmentFile([string]$Path) {
    $values = [ordered]@{}
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in Get-Content -LiteralPath $Path) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
                $values[$matches[1]] = $matches[2]
            }
        }
    }
    return $values
}

function Write-EnvironmentFile([string]$Path, [System.Collections.IDictionary]$Values) {
    $lines = foreach ($key in $Values.Keys) {
        "$key=$($Values[$key])"
    }
    Set-Content -LiteralPath $Path -Value $lines -Encoding utf8NoBOM
}

function Invoke-Keycloak([string[]]$Arguments) {
    $output = & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Keycloak command failed'
    }
    return $output
}

function Ensure-KeycloakUser([string]$Username, [string]$FirstName, [string]$Password) {
    $existing = @(Invoke-Keycloak @('get', 'users', '-r', 'rtk-crm', '-q', "username=$Username", '-q', 'exact=true') | ConvertFrom-Json)
    $definition = @{
        username = $Username
        firstName = $FirstName
        lastName = 'Demo'
        email = "$Username@demo.rtk.local"
        enabled = $true
        emailVerified = $true
        requiredActions = @()
    } | ConvertTo-Json -Compress
    if ($existing.Count -eq 0) {
        $definition | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create users -r rtk-crm -f -
        if ($LASTEXITCODE -ne 0) {
            throw "Cannot create Keycloak user $Username"
        }
        $existing = @(Invoke-Keycloak @('get', 'users', '-r', 'rtk-crm', '-q', "username=$Username", '-q', 'exact=true') | ConvertFrom-Json)
        if ($existing.Count -ne 1) {
            throw "Cannot resolve Keycloak subject for $Username"
        }
    }
    if ($existing.Count -ne 1) {
        throw "Keycloak user $Username is not unique"
    }
    $credential = @{ type = 'password'; value = $Password; temporary = $false } | ConvertTo-Json -Compress
    $credential | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "users/$($existing[0].id)/reset-password" -r rtk-crm -f -
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot set Keycloak password for $Username"
    }
    $definition | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "users/$($existing[0].id)" -r rtk-crm -f -
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot update Keycloak user $Username"
    }
    return $existing[0].id
}

function Invoke-MoodleDemo {
    $moodleEnvFile = Join-Path $projectRoot 'infra/moodle/.env.local'
    $moodleValues = Read-EnvironmentFile $moodleEnvFile
    foreach ($pair in @{ MOODLE_DB_NAME = 'moodle'; MOODLE_DB_USER = 'moodle'; MOODLE_ADMIN_USERNAME = 'admin' }.GetEnumerator()) {
        if ([string]::IsNullOrWhiteSpace($moodleValues[$pair.Key])) {
            $moodleValues[$pair.Key] = $pair.Value
        }
    }
    foreach ($key in @('MOODLE_DB_PASSWORD', 'MOODLE_DB_ROOT_PASSWORD', 'MOODLE_ADMIN_PASSWORD', 'MOODLE_JURY_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace($moodleValues[$key])) {
            $moodleValues[$key] = New-Secret
        }
    }
    Write-EnvironmentFile $moodleEnvFile $moodleValues
    $moodleCompose = @('compose', '-p', 'rtk-crm-moodle', '--env-file', $moodleEnvFile, '-f', (Join-Path $projectRoot 'infra/moodle/compose.yaml'))
    & docker @moodleCompose up -d --wait --wait-timeout 1200
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot start the demo Moodle'
    }
    $env:MOODLE_JURY_PASSWORD = $moodleValues['MOODLE_JURY_PASSWORD']
    try {
        $setupOutput = Get-Content -LiteralPath (Join-Path $projectRoot 'infra/moodle/demo-setup.php') -Raw |
            & docker @moodleCompose exec -T -e MOODLE_JURY_PASSWORD -u daemon moodle /opt/bitnami/php/bin/php
    }
    finally {
        Remove-Item Env:MOODLE_JURY_PASSWORD
    }
    if ($LASTEXITCODE -ne 0) {
        $setupOutput | Where-Object { $_ -notmatch '^TOKEN=' } | ForEach-Object { [Console]::Error.WriteLine($_) }
        throw 'Moodle demo setup script failed'
    }
    $reported = @{}
    foreach ($line in $setupOutput) {
        if ($line -match '^(COURSE_IDS|TOKEN|DEMO_JAVA_COURSE|DEMO_DATA_GROUP)=(.+)$') {
            $reported[$matches[1]] = $matches[2].Trim()
        }
    }
    foreach ($key in @('COURSE_IDS', 'TOKEN', 'DEMO_JAVA_COURSE', 'DEMO_DATA_GROUP')) {
        if ([string]::IsNullOrWhiteSpace($reported[$key])) {
            throw 'Moodle demo setup did not report course ids, demo mapping keys and token'
        }
    }
    $values['MOODLE_BASE_URL'] = $moodleDemoUrl
    $values['MOODLE_TOKEN'] = $reported['TOKEN']
    $values['MOODLE_COURSE_IDS'] = $reported['COURSE_IDS']
    $values['MOODLE_DEMO_JAVA_COURSE'] = $reported['DEMO_JAVA_COURSE']
    $values['MOODLE_DEMO_DATA_GROUP'] = $reported['DEMO_DATA_GROUP']
}

function ConvertTo-YamlLiteral([string]$Value) {
    return "'$($Value.Replace("'", "''"))'"
}

$values = Read-EnvironmentFile $envFilePath
if (-not [string]::IsNullOrWhiteSpace($env:DEMO_LMS)) {
    $values['DEMO_LMS'] = $env:DEMO_LMS
}
$defaults = [ordered]@{
    POSTGRES_SUPERUSER = 'postgres'
    CRM_DB_NAME = 'rtk_crm'
    CRM_DB_USER = 'crm'
    KEYCLOAK_DB_NAME = 'keycloak'
    KEYCLOAK_DB_USER = 'keycloak'
    KEYCLOAK_ADMIN_USERNAME = 'bootstrap-admin'
    PUBLIC_ORIGIN = 'http://rtk.localhost:8081'
    SITE_BASE_URL = 'http://site-fixture:8080'
    DEMO_LMS = 'true'
    SOURCES_SYNC_CRON = '0 0 * * * *'
}
$secrets = @(
    'POSTGRES_SUPERUSER_PASSWORD',
    'CRM_DB_PASSWORD',
    'KEYCLOAK_DB_PASSWORD',
    'KEYCLOAK_ADMIN_PASSWORD',
    'CRM_OIDC_CLIENT_SECRET',
    'DEMO_USER_PASSWORD',
    'SITE_TOKEN'
)
foreach ($pair in $defaults.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace($values[$pair.Key])) {
        $values[$pair.Key] = $pair.Value
    }
}
foreach ($key in $secrets) {
    if ([string]::IsNullOrWhiteSpace($values[$key])) {
        $values[$key] = New-Secret
    }
}
if ($values['DEMO_LMS'] -cne 'true' -and $values['DEMO_LMS'] -cne 'false') {
    throw 'DEMO_LMS must be true or false'
}
$moodleDemoUrl = 'http://moodle:8080'
if ($values['DEMO_LMS'] -eq 'true') {
    if ([string]::IsNullOrWhiteSpace($values['MOODLE_BASE_URL']) -or $values['MOODLE_BASE_URL'] -eq $moodleDemoUrl) {
        Push-Location $projectRoot
        try {
            Invoke-MoodleDemo
        }
        finally {
            Pop-Location
        }
    }
}
elseif ($values['MOODLE_BASE_URL'] -eq $moodleDemoUrl) {
    foreach ($key in @('MOODLE_BASE_URL', 'MOODLE_TOKEN', 'MOODLE_COURSE_IDS', 'MOODLE_DEMO_JAVA_COURSE', 'MOODLE_DEMO_DATA_GROUP')) {
        $values[$key] = ''
    }
}
Write-EnvironmentFile $envFilePath $values
foreach ($key in $values.Keys) {
    Set-Item -Path "Env:$key" -Value $values[$key]
}

Push-Location $projectRoot
try {
    & docker compose --env-file $envFilePath up -d --wait postgres keycloak
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot start PostgreSQL and Keycloak'
    }
    Invoke-Keycloak @(
        'config', 'credentials',
        '--server', 'http://localhost:8080',
        '--realm', 'master',
        '--user', $values['KEYCLOAK_ADMIN_USERNAME'],
        '--password', $values['KEYCLOAK_ADMIN_PASSWORD']
    ) | Out-Null
    Invoke-Keycloak @('update', 'realms/rtk-crm', '-f', '/opt/keycloak/data/import/rtk-crm-realm.json') | Out-Null
    $webOrigins = @($values['PUBLIC_ORIGIN'])
    if ($values['PUBLIC_ORIGIN'] -eq 'http://rtk.localhost:8081') {
        $webOrigins += 'http://localhost:8081'
    }
    $client = @(Invoke-Keycloak @('get', 'clients', '-r', 'rtk-crm', '-q', 'clientId=crm-bff') | ConvertFrom-Json)
    $clientDefinition = @{
        clientId = 'crm-bff'
        enabled = $true
        protocol = 'openid-connect'
        publicClient = $false
        standardFlowEnabled = $true
        directAccessGrantsEnabled = $false
        serviceAccountsEnabled = $false
        secret = $values['CRM_OIDC_CLIENT_SECRET']
        redirectUris = @($webOrigins | ForEach-Object { "$_/api/auth/callback/keycloak" })
        webOrigins = $webOrigins
        attributes = @{
            'post.logout.redirect.uris' = @($webOrigins | ForEach-Object { $_; "$_/*" }) -join '##'
        }
    } | ConvertTo-Json -Compress
    if ($client.Count -eq 0) {
        $clientDefinition | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create clients -r rtk-crm -f -
        if ($LASTEXITCODE -ne 0) {
            throw 'Cannot create the CRM OIDC client'
        }
        $client = @(Invoke-Keycloak @('get', 'clients', '-r', 'rtk-crm', '-q', 'clientId=crm-bff') | ConvertFrom-Json)
    }
    if ($client.Count -ne 1) {
        throw 'CRM OIDC client is not unique'
    }
    $clientDefinition | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "clients/$($client[0].id)" -r rtk-crm -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot update the CRM OIDC client'
    }
    $password = $values['DEMO_USER_PASSWORD']
    $subjects = [ordered]@{
        'kam-a' = Ensure-KeycloakUser 'kam-a' 'КАМ А' $password
        'kam-b' = Ensure-KeycloakUser 'kam-b' 'КАМ Б' $password
        'kam-c' = Ensure-KeycloakUser 'kam-c' 'КАМ В' $password
        'leader' = Ensure-KeycloakUser 'leader' 'Руководитель' $password
        'admin' = Ensure-KeycloakUser 'admin' 'Администратор' $password
        'unprofiled' = Ensure-KeycloakUser 'unprofiled' 'Без профиля CRM' $password
    }
    $issuer = "$($values['PUBLIC_ORIGIN'])/idp/realms/rtk-crm"
    $identityFile = Join-Path $projectRoot '.demo-identities.yml'
    $identityYaml = @(
        'app:',
        '  demo-bootstrap:',
        '    identities:',
        "      - key: 'kam-a'",
        "        issuer: $(ConvertTo-YamlLiteral $issuer)",
        "        subject: $(ConvertTo-YamlLiteral $subjects['kam-a'])",
        "        display-name: 'КАМ А'",
        "        role: USER",
        "        team-key: 'team-a'",
        "      - key: 'kam-b'",
        "        issuer: $(ConvertTo-YamlLiteral $issuer)",
        "        subject: $(ConvertTo-YamlLiteral $subjects['kam-b'])",
        "        display-name: 'КАМ Б'",
        "        role: USER",
        "        team-key: 'team-b'",
        "      - key: 'kam-c'",
        "        issuer: $(ConvertTo-YamlLiteral $issuer)",
        "        subject: $(ConvertTo-YamlLiteral $subjects['kam-c'])",
        "        display-name: 'КАМ В'",
        "        role: USER",
        "        team-key: 'team-a'",
        "      - key: 'leader'",
        "        issuer: $(ConvertTo-YamlLiteral $issuer)",
        "        subject: $(ConvertTo-YamlLiteral $subjects['leader'])",
        "        display-name: 'Руководитель'",
        "        role: LEADER",
        "        team-key: 'team-a'",
        "      - key: 'admin'",
        "        issuer: $(ConvertTo-YamlLiteral $issuer)",
        "        subject: $(ConvertTo-YamlLiteral $subjects['admin'])",
        "        display-name: 'Администратор'",
        "        role: ADMIN",
        "        team-key: 'team-a'",
        '    organizations:',
        "      - name: 'Университет А'",
        "        type: UNIVERSITY",
        "        team-key: 'team-a'",
        "        owner-key: 'kam-a'",
        "      - name: 'Университет Б'",
        "        type: UNIVERSITY",
        "        team-key: 'team-b'",
        "        owner-key: 'kam-b'",
        "      - name: 'Университет C — требует назначения'",
        "        type: UNIVERSITY",
        "        team-key: 'team-a'"
    )
    if ($values['MOODLE_BASE_URL'] -eq $moodleDemoUrl -and
        -not [string]::IsNullOrWhiteSpace($values['MOODLE_DEMO_JAVA_COURSE']) -and
        -not [string]::IsNullOrWhiteSpace($values['MOODLE_DEMO_DATA_GROUP'])) {
        $identityYaml += @(
            '    learning-mappings:',
            '      - kind: COURSE',
            "        external-key: $(ConvertTo-YamlLiteral $values['MOODLE_DEMO_JAVA_COURSE'])",
            "        organization: 'Университет А'",
            "        program: 'Демо-программа: цифровой университет'",
            '      - kind: GROUP',
            "        external-key: $(ConvertTo-YamlLiteral $values['MOODLE_DEMO_DATA_GROUP'])",
            "        organization: 'Университет Б'",
            "        program: 'Демо-программа: анализ данных'"
        )
    }
    Set-Content -LiteralPath $identityFile -Value $identityYaml -Encoding utf8NoBOM
    if ($values['SITE_BASE_URL'] -eq 'http://site-fixture:8080') {
        & docker compose --env-file $envFilePath --profile demo-sources up -d --wait site-fixture
        if ($LASTEXITCODE -ne 0) {
            throw 'Cannot start the demo site fixture'
        }
    }
    $moodleFiles = @()
    if ($values['MOODLE_BASE_URL'] -eq $moodleDemoUrl) {
        $separator = if ($env:COMPOSE_PATH_SEPARATOR) { $env:COMPOSE_PATH_SEPARATOR } else { [System.IO.Path]::PathSeparator }
        $composeFiles = if ($env:COMPOSE_FILE) { $env:COMPOSE_FILE } else { 'compose.yaml' }
        $files = @($composeFiles.Split($separator)) + 'infra/moodle/compose.crm.yaml'
        if (-not [string]::IsNullOrWhiteSpace($values['MOODLE_PUBLIC_HOST'])) {
            $files += 'infra/moodle/compose.crm.prod.yaml'
        }
        foreach ($file in $files) {
            $moodleFiles += @('-f', $file)
        }
    }
    & docker compose --env-file $envFilePath @moodleFiles up -d --wait --build backend clamav web
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot start CRM services'
    }
    & docker compose --env-file $envFilePath @moodleFiles up -d --wait --force-recreate --no-deps web
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot restart the CRM web server'
    }
    & docker compose --env-file $envFilePath @moodleFiles --profile demo-bootstrap run --rm --build backend-bootstrap
    if ($LASTEXITCODE -ne 0) {
        throw 'CRM demo bootstrap failed'
    }
    Write-Output "Demo bootstrap completed. Local credentials are in $envFilePath"
    if ($values['MOODLE_BASE_URL'] -eq $moodleDemoUrl) {
        Write-Output "Demo Moodle: http://localhost:8082, administrator and read-only jury (crm-jury) credentials are in $(Join-Path $projectRoot 'infra/moodle/.env.local')"
    }
}
finally {
    Pop-Location
}
