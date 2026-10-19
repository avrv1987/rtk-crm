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

function Protect-SecretFile([string]$Path) {
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls $Path /inheritance:r /grant:r "*${sid}:(F)" '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot restrict access to $Path"
    }
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

function Get-KeycloakUsers([string]$Username) {
    return @(Invoke-Keycloak @('get', 'users', '-r', 'rtk-crm', '-q', "username=$Username", '-q', 'exact=true') | ConvertFrom-Json)
}

function New-KeycloakUser([string]$Username, [string]$Definition) {
    $Definition | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create users -r rtk-crm -f -
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot create Keycloak user $Username"
    }
    $created = Get-KeycloakUsers $Username
    if ($created.Count -ne 1) {
        throw "Cannot resolve Keycloak subject for $Username"
    }
    return $created
}

function Set-KeycloakPassword([string]$Username, [string]$Id, [string]$Password, [bool]$Temporary) {
    $credential = @{ type = 'password'; value = $Password; temporary = $Temporary } | ConvertTo-Json -Compress
    $credential | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "users/$Id/reset-password" -r rtk-crm -f -
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot set Keycloak password for $Username"
    }
}

function Ensure-KeycloakUser([string]$Username, [string]$FirstName, [string]$Password) {
    $secured = $values['DEMO_ACCOUNTS_SECURED'] -eq 'true'
    $existing = Get-KeycloakUsers $Username
    $definition = @{
        username = $Username
        firstName = $FirstName
        lastName = 'Demo'
        email = "$Username@demo.rtk.local"
        enabled = -not $secured
        emailVerified = $true
        requiredActions = @()
    } | ConvertTo-Json -Compress
    if ($existing.Count -eq 0) {
        $existing = New-KeycloakUser $Username $definition
    }
    if ($existing.Count -ne 1) {
        throw "Keycloak user $Username is not unique"
    }
    if (-not $secured) {
        Set-KeycloakPassword $Username $existing[0].id $Password $false
        $definition | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "users/$($existing[0].id)" -r rtk-crm -f -
        if ($LASTEXITCODE -ne 0) {
            throw "Cannot update Keycloak user $Username"
        }
    }
    return $existing[0].id
}

function New-KeycloakResource([string]$Path, [hashtable]$Definition) {
    $id = $Definition | ConvertTo-Json -Compress -Depth 5 |
        & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create $Path -r rtk-crm -f - -i
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($id)) {
        throw "Cannot create Keycloak $Path"
    }
    return "$id".Trim()
}

function Add-KeycloakExecution([string]$ParentFlow, [string]$Authenticator, [string]$Requirement, [int]$Priority) {
    return New-KeycloakResource 'authentication/executions' @{
        parentFlow = $ParentFlow; authenticator = $Authenticator; requirement = $Requirement; priority = $Priority
        authenticatorFlow = $false
    }
}

function Add-KeycloakSubflow([string]$ParentFlow, [string]$Alias, [string]$Requirement, [int]$Priority) {
    $id = New-KeycloakResource 'authentication/flows' @{ alias = $Alias; providerId = 'basic-flow'; topLevel = $false; builtIn = $false }
    New-KeycloakResource 'authentication/executions' @{
        parentFlow = $ParentFlow; flowId = $id; requirement = $Requirement; priority = $Priority; authenticatorFlow = $true
    } | Out-Null
    return $id
}

function Clear-PendingTotp([string]$Role) {
    foreach ($member in (Invoke-Keycloak @('get', "roles/$Role/users", '-r', 'rtk-crm', '-q', 'max=10000') | ConvertFrom-Json)) {
        $user = Invoke-Keycloak @('get', "users/$($member.id)", '-r', 'rtk-crm') | ConvertFrom-Json
        if (@($user.requiredActions) -ccontains 'CONFIGURE_TOTP') {
            $actions = @(@($user.requiredActions) | Where-Object { $_ -cne 'CONFIGURE_TOTP' })
            @{ requiredActions = $actions } | ConvertTo-Json -Compress |
                & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "users/$($member.id)" -r rtk-crm -f -
            if ($LASTEXITCODE -ne 0) {
                throw "Cannot update Keycloak user $($member.username)"
            }
        }
    }
}

function Set-PrivilegedSecondFactor {
    $role = 'crm-privileged'
    $flow = 'crm-browser'
    $roles = Invoke-Keycloak @('get', 'roles', '-r', 'rtk-crm') | ConvertFrom-Json
    if (-not (@($roles.name) -ccontains $role)) {
        Invoke-Keycloak @('create', 'roles', '-r', 'rtk-crm', '-s', "name=$role", '-s', 'description=CRM: second factor is required at sign-in') | Out-Null
    }
    Invoke-Keycloak @('update', 'realms/rtk-crm', '-s', 'browserFlow=browser') | Out-Null
    foreach ($item in (Invoke-Keycloak @('get', 'authentication/flows', '-r', 'rtk-crm') | ConvertFrom-Json)) {
        if ($item.alias -ceq $flow) {
            Invoke-Keycloak @('delete', "authentication/flows/$($item.id)", '-r', 'rtk-crm') | Out-Null
        }
    }
    if ($values['MFA_REQUIRED_FOR_PRIVILEGED'] -ne 'true') {
        Clear-PendingTotp $role
        return
    }
    $top = New-KeycloakResource 'authentication/flows' @{
        alias = $flow; description = "Browser sign-in, OTP for $role"; providerId = 'basic-flow'; topLevel = $true; builtIn = $false
    }
    Add-KeycloakExecution $top 'auth-cookie' 'ALTERNATIVE' 10 | Out-Null
    $forms = Add-KeycloakSubflow $top "$flow forms" 'ALTERNATIVE' 20
    Add-KeycloakExecution $forms 'auth-username-password-form' 'REQUIRED' 10 | Out-Null
    $otp = Add-KeycloakSubflow $forms "$flow privileged OTP" 'CONDITIONAL' 20
    $condition = Add-KeycloakExecution $otp 'conditional-user-role' 'REQUIRED' 10
    @{ alias = "$flow role"; config = @{ condUserRole = $role; negate = 'false' } } | ConvertTo-Json -Compress |
        & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create "authentication/executions/$condition/config" -r rtk-crm -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot configure the privileged role condition'
    }
    Add-KeycloakExecution $otp 'auth-otp-form' 'REQUIRED' 20 | Out-Null
    Invoke-Keycloak @('update', 'realms/rtk-crm', '-s', "browserFlow=$flow") | Out-Null
}

function Set-SecondFactorRequired([string]$Username) {
    Invoke-Keycloak @('add-roles', '-r', 'rtk-crm', '--uusername', $Username, '--rolename', 'crm-privileged') | Out-Null
}

function Set-CrmRealmRoles {
    $existing = @((Invoke-Keycloak @('get', 'roles', '-r', 'rtk-crm') | ConvertFrom-Json).name)
    foreach ($role in @('crm-user', 'crm-leader', 'crm-admin', 'crm-management', 'crm-partner')) {
        if (-not ($existing -ccontains $role)) {
            Invoke-Keycloak @('create', 'roles', '-r', 'rtk-crm', '-s', "name=$role", '-s', 'description=CRM role') | Out-Null
        }
    }
    $composites = @((Invoke-Keycloak @('get', 'roles/crm-admin/composites', '-r', 'rtk-crm') | ConvertFrom-Json).name)
    if (-not ($composites -ccontains 'crm-privileged')) {
        Invoke-Keycloak @('add-roles', '-r', 'rtk-crm', '--rname', 'crm-admin', '--rolename', 'crm-privileged') | Out-Null
    }
}

function Set-CrmRolesMapper([string]$ClientId) {
    $name = 'crm realm roles'
    $mapper = [ordered]@{
        name = $name
        protocol = 'openid-connect'
        protocolMapper = 'oidc-usermodel-realm-role-mapper'
        config = [ordered]@{
            'claim.name' = 'realm_access.roles'
            multivalued = 'true'
            'jsonType.label' = 'String'
            'id.token.claim' = 'true'
            'access.token.claim' = 'false'
            'userinfo.token.claim' = 'false'
            'introspection.token.claim' = 'false'
        }
    }
    $path = "clients/$ClientId/protocol-mappers/models"
    $existing = @(Invoke-Keycloak @('get', $path, '-r', 'rtk-crm') | ConvertFrom-Json | Where-Object { $_.name -ceq $name })
    if ($existing.Count -eq 0) {
        $mapper | ConvertTo-Json -Compress -Depth 5 |
            & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create $path -r rtk-crm -f -
    }
    else {
        $mapper['id'] = $existing[0].id
        $mapper | ConvertTo-Json -Compress -Depth 5 |
            & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "$path/$($existing[0].id)" -r rtk-crm -f -
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot configure the CRM realm roles mapper'
    }
}

function Set-CrmRole([string]$Username, [string]$Role) {
    Invoke-Keycloak @('add-roles', '-r', 'rtk-crm', '--uusername', $Username, '--rolename', "crm-$($Role.ToLowerInvariant())") | Out-Null
}

# MCP для ИИ-агентов (docs/mcp.md). Клиентский scope crm-mcp кладёт в access token aud=crm-mcp и роли CRM; публичный
# клиент crm-mcp-client входит по PKCE с экраном согласия, без offline_access. Регистрации клиентов «на лету» (DCR) нет:
# адреса возврата клиентов перечисляются в MCP_REDIRECT_URIS. При MCP_ENABLED=false уже заведённый клиент отключается,
# а realm не пересоздаётся ни в каком случае.
function Get-KeycloakScopeId([string]$Name) {
    return @(Invoke-Keycloak @('get', 'client-scopes', '-r', 'rtk-crm') | ConvertFrom-Json | Where-Object { $_.name -ceq $Name } |
        ForEach-Object { $_.id })
}

function Set-KeycloakScopeMapper([string]$ScopeId, [string]$Name, [string]$Mapper, [hashtable]$Config) {
    $definition = [ordered]@{ name = $Name; protocol = 'openid-connect'; protocolMapper = $Mapper; config = $Config }
    $path = "client-scopes/$ScopeId/protocol-mappers/models"
    $existing = @(Invoke-Keycloak @('get', $path, '-r', 'rtk-crm') | ConvertFrom-Json | Where-Object { $_.name -ceq $Name })
    if ($existing.Count -eq 0) {
        $definition | ConvertTo-Json -Compress -Depth 5 |
            & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create $path -r rtk-crm -f -
    }
    else {
        $definition['id'] = $existing[0].id
        $definition | ConvertTo-Json -Compress -Depth 5 |
            & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "$path/$($existing[0].id)" -r rtk-crm -f -
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot configure the Keycloak mapper $Name"
    }
}

function Set-McpClient {
    $client = @(Invoke-Keycloak @('get', 'clients', '-r', 'rtk-crm', '-q', 'clientId=crm-mcp-client') | ConvertFrom-Json)
    if ($client.Count -gt 1) {
        throw 'MCP client is not unique'
    }
    if ($values['MCP_ENABLED'] -ne 'true') {
        if ($client.Count -eq 1) {
            Invoke-Keycloak @('update', "clients/$($client[0].id)", '-r', 'rtk-crm', '-s', 'enabled=false') | Out-Null
        }
        return
    }
    $scope = Get-KeycloakScopeId 'crm-mcp'
    if ($scope.Count -eq 0) {
        $scope = @(New-KeycloakResource 'client-scopes' @{
            name = 'crm-mcp'
            protocol = 'openid-connect'
            attributes = @{
                'include.in.token.scope' = 'true'
                'display.on.consent.screen' = 'true'
                'consent.screen.text' = 'Доступ ИИ-агента к CRM от вашего имени, только чтение'
            }
        })
    }
    if ($scope.Count -ne 1) {
        throw 'MCP client scope is not unique'
    }
    Set-KeycloakScopeMapper $scope[0] 'crm-mcp audience' 'oidc-audience-mapper' @{
        'included.custom.audience' = 'crm-mcp'; 'access.token.claim' = 'true'; 'id.token.claim' = 'false'
        'introspection.token.claim' = 'true'
    }
    Set-KeycloakScopeMapper $scope[0] 'crm realm roles' 'oidc-usermodel-realm-role-mapper' @{
        'claim.name' = 'realm_access.roles'; multivalued = 'true'; 'jsonType.label' = 'String'; 'id.token.claim' = 'false'
        'access.token.claim' = 'true'; 'userinfo.token.claim' = 'false'; 'introspection.token.claim' = 'true'
    }
    $definition = @{
        clientId = 'crm-mcp-client'
        name = 'MCP: ИИ-агенты'
        enabled = $true
        protocol = 'openid-connect'
        publicClient = $true
        standardFlowEnabled = $true
        implicitFlowEnabled = $false
        directAccessGrantsEnabled = $false
        serviceAccountsEnabled = $false
        consentRequired = $true
        fullScopeAllowed = $false
        redirectUris = @($values['MCP_REDIRECT_URIS'] -split ',' | Where-Object { $_ -ne '' })
        webOrigins = @('+')
        attributes = @{
            'pkce.code.challenge.method' = 'S256'
            'access.token.lifespan' = '300'
            'oauth2.device.authorization.grant.enabled' = 'false'
        }
    }
    if ($client.Count -eq 0) {
        $client = @(@{ id = (New-KeycloakResource 'clients' $definition) })
    }
    $definition | ConvertTo-Json -Compress -Depth 5 |
        & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "clients/$($client[0].id)" -r rtk-crm -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot update the MCP client'
    }
    Invoke-Keycloak @('update', "clients/$($client[0].id)/default-client-scopes/$($scope[0])", '-r', 'rtk-crm') | Out-Null
    # В токен агента — только роли CRM: fullScopeAllowed выключен, роли crm-* привязаны к scope crm-mcp.
    foreach ($role in @('crm-user', 'crm-leader', 'crm-admin', 'crm-management', 'crm-partner')) {
        $roleJson = (Invoke-Keycloak @('get', "roles/$role", '-r', 'rtk-crm')) -join "`n"
        "[$roleJson]" | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create `
            "client-scopes/$($scope[0])/scope-mappings/realm" -r rtk-crm -f -
        if ($LASTEXITCODE -ne 0) {
            throw 'Cannot map CRM roles to the MCP scope'
        }
    }
    # Scope записи — необязательный: клиент получает его, только если явно запросил, а сотрудник согласился. Он даёт
    # лишь подготовку плана; записывает план сам сотрудник на экране CRM. Без него у клиента остаётся чтение.
    $writeScope = Get-KeycloakScopeId 'crm-mcp.write'
    if ($writeScope.Count -eq 0) {
        $writeScope = @(New-KeycloakResource 'client-scopes' @{
            name = 'crm-mcp.write'
            protocol = 'openid-connect'
            attributes = @{
                'include.in.token.scope' = 'true'
                'display.on.consent.screen' = 'true'
                'consent.screen.text' = 'Подготовка записей ИИ-агентом; записываете вы сами на экране CRM'
            }
        })
    }
    if ($writeScope.Count -ne 1) {
        throw 'MCP write scope is not unique'
    }
    Invoke-Keycloak @('update', "clients/$($client[0].id)/optional-client-scopes/$($writeScope[0])", '-r', 'rtk-crm') | Out-Null
    $offline = Get-KeycloakScopeId 'offline_access'
    if ($offline.Count -eq 1) {
        $optional = @(Invoke-Keycloak @('get', "clients/$($client[0].id)/optional-client-scopes", '-r', 'rtk-crm') | ConvertFrom-Json |
            ForEach-Object { $_.id })
        if ($optional -contains $offline[0]) {
            Invoke-Keycloak @('delete', "clients/$($client[0].id)/optional-client-scopes/$($offline[0])", '-r', 'rtk-crm') | Out-Null
        }
    }
}

function Ensure-CrmAdministrator {
    $username = $values['CRM_ADMIN_USERNAME']
    $existing = Get-KeycloakUsers $username
    if ($existing.Count -eq 0) {
        $definition = @{
            username = $username
            firstName = $values['CRM_ADMIN_DISPLAY_NAME']
            lastName = 'CRM'
            email = $values['CRM_ADMIN_EMAIL']
            enabled = $true
            emailVerified = $true
            requiredActions = @()
        } | ConvertTo-Json -Compress
        $existing = New-KeycloakUser $username $definition
        Set-KeycloakPassword $username $existing[0].id $values['CRM_ADMIN_PASSWORD'] $true
    }
    if ($existing.Count -ne 1) {
        throw "Keycloak user $username is not unique"
    }
    if ([string]::IsNullOrWhiteSpace($existing[0].email)) {
        @{ email = $values['CRM_ADMIN_EMAIL']; emailVerified = $true } | ConvertTo-Json -Compress |
            & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "users/$($existing[0].id)" -r rtk-crm -f -
        if ($LASTEXITCODE -ne 0) {
            throw "Cannot update Keycloak user $username"
        }
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
    Protect-SecretFile $moodleEnvFile
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
        $setupOutput | Where-Object { $_ -notmatch 'TOKEN=' } | ForEach-Object { [Console]::Error.WriteLine($_) }
        throw 'Moodle demo setup script failed'
    }
    $reported = @{}
    foreach ($line in $setupOutput) {
        if ($line -match '^(COURSE_IDS|TOKEN|WRITE_TOKEN|STUDENT_ROLE_ID|DEMO_JAVA_COURSE|DEMO_DATA_GROUP|DEMO_TEACH_COURSE|DEMO_WEB_COURSE|DEMO_EMPTY_COURSE)=(.+)$') {
            $reported[$matches[1]] = $matches[2].Trim()
        }
    }
    foreach ($key in @('COURSE_IDS', 'TOKEN', 'WRITE_TOKEN', 'STUDENT_ROLE_ID', 'DEMO_JAVA_COURSE', 'DEMO_DATA_GROUP', 'DEMO_TEACH_COURSE', 'DEMO_WEB_COURSE', 'DEMO_EMPTY_COURSE')) {
        if ([string]::IsNullOrWhiteSpace($reported[$key])) {
            throw 'Moodle demo setup did not report course ids, demo mapping keys and token'
        }
    }
    $values['MOODLE_BASE_URL'] = $moodleDemoUrl
    $values['MOODLE_TOKEN'] = $reported['TOKEN']
    $values['MOODLE_COURSE_IDS'] = $reported['COURSE_IDS']
    $values['MOODLE_DEMO_JAVA_COURSE'] = $reported['DEMO_JAVA_COURSE']
    $values['MOODLE_DEMO_DATA_GROUP'] = $reported['DEMO_DATA_GROUP']
    $values['MOODLE_DEMO_TEACH_COURSE'] = $reported['DEMO_TEACH_COURSE']
    $values['MOODLE_DEMO_WEB_COURSE'] = $reported['DEMO_WEB_COURSE']
    $values['MOODLE_DEMO_EMPTY_COURSE'] = $reported['DEMO_EMPTY_COURSE']
    $values['MOODLE_WRITE_ENABLED'] = 'true'
    $values['MOODLE_WRITE_TOKEN'] = $reported['WRITE_TOKEN']
    $values['MOODLE_STUDENT_ROLE_ID'] = $reported['STUDENT_ROLE_ID']
}

function ConvertTo-YamlLiteral([string]$Value) {
    return "'$($Value.Replace("'", "''"))'"
}

$values = Read-EnvironmentFile $envFilePath
foreach ($key in @('DEMO_DATA', 'DEMO_LMS')) {
    $override = [Environment]::GetEnvironmentVariable($key)
    if (-not [string]::IsNullOrWhiteSpace($override)) {
        $values[$key] = $override
    }
}
if ([string]::IsNullOrWhiteSpace($values['DEMO_DATA'])) {
    $values['DEMO_DATA'] = 'true'
}
if ($values['DEMO_DATA'] -cne 'true' -and $values['DEMO_DATA'] -cne 'false') {
    throw 'DEMO_DATA must be true or false'
}
if ([string]::IsNullOrWhiteSpace($values['MODULE_ENROLMENT_ENABLED']) -and -not [string]::IsNullOrWhiteSpace($values['ENROLMENT_ENABLED'])) {
    $values['MODULE_ENROLMENT_ENABLED'] = $values['ENROLMENT_ENABLED']
}
$siteFixtureUrl = 'http://site-fixture:8080'
$laborMarketFixtureUrl = 'http://labor-market-fixture:8080'
$crmStubUrl = 'http://crm-stub:8080'
$defaults = [ordered]@{
    POSTGRES_SUPERUSER = 'postgres'
    CRM_DB_NAME = 'rtk_crm'
    CRM_DB_USER = 'crm'
    KEYCLOAK_DB_NAME = 'keycloak'
    KEYCLOAK_DB_USER = 'keycloak'
    KEYCLOAK_ADMIN_USERNAME = 'bootstrap-admin'
    PUBLIC_ORIGIN = 'http://rtk.localhost:8081'
    SOURCES_SYNC_CRON = '0 0 * * * *'
    ENROLMENT_ACTIVE_KEY_VERSION = 'v1'
    MFA_REQUIRED_FOR_PRIVILEGED = 'false'
    MCP_ENABLED = 'false'
    MCP_REDIRECT_URIS = 'http://127.0.0.1:8976/callback,http://localhost:6274/oauth/callback,http://localhost:6274/oauth/callback/debug'
}
$secrets = @(
    'POSTGRES_SUPERUSER_PASSWORD',
    'CRM_DB_PASSWORD',
    'KEYCLOAK_DB_PASSWORD',
    'KEYCLOAK_ADMIN_PASSWORD',
    'CRM_OIDC_CLIENT_SECRET',
    'CRM_ACCOUNT_SYNC_CLIENT_SECRET',
    'ENROLMENT_KEYS_V1',
    'ENROLMENT_FINGERPRINT_KEY',
    'SOURCES_SETTINGS_KEY',
    'BACKUP_ENCRYPTION_KEY'
)
if ($values['DEMO_DATA'] -eq 'true') {
    $defaults['SITE_BASE_URL'] = $siteFixtureUrl
    $defaults['LABOR_MARKET_BASE_URL'] = $laborMarketFixtureUrl
    $defaults['CRM_BASE_URL'] = $crmStubUrl
    $defaults['DEMO_LMS'] = 'true'
    $defaults['MODULE_ENROLMENT_ENABLED'] = 'true'
    $defaults['MODULE_PARTNER_ENABLED'] = 'true'
    $defaults['COMPOSE_PROFILES'] = 'security,demo'
    $secrets += @('DEMO_USER_PASSWORD', 'SITE_TOKEN', 'LABOR_MARKET_TOKEN', 'CRM_TOKEN')
}
else {
    if ($values['DEMO_LMS'] -eq 'true') {
        throw 'DEMO_LMS=true requires DEMO_DATA=true'
    }
    if ($values['SITE_BASE_URL'] -eq $siteFixtureUrl) {
        $values['SITE_BASE_URL'] = ''
    }
    if ($values['LABOR_MARKET_BASE_URL'] -eq $laborMarketFixtureUrl) {
        $values['LABOR_MARKET_BASE_URL'] = ''
    }
    if ($values['CRM_BASE_URL'] -eq $crmStubUrl) {
        $values['CRM_BASE_URL'] = ''
    }
    $defaults['DEMO_LMS'] = 'false'
    $defaults['MODULE_ENROLMENT_ENABLED'] = 'true'
    $defaults['MODULE_PARTNER_ENABLED'] = 'true'
    $defaults['COMPOSE_PROFILES'] = 'security'
    $defaults['CRM_ADMIN_USERNAME'] = 'admin'
    $defaults['CRM_ADMIN_DISPLAY_NAME'] = 'Администратор'
    $defaults['CRM_ADMIN_EMAIL'] = 'admin@rtk-crm.local'
    $secrets += @('CRM_ADMIN_PASSWORD')
}
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
if ($values['MFA_REQUIRED_FOR_PRIVILEGED'] -cne 'true' -and $values['MFA_REQUIRED_FOR_PRIVILEGED'] -cne 'false') {
    throw 'MFA_REQUIRED_FOR_PRIVILEGED must be true or false'
}
foreach ($key in @('MODULE_ENROLMENT_ENABLED', 'MODULE_PARTNER_ENABLED', 'MCP_ENABLED')) {
    if ($values[$key] -cne 'true' -and $values[$key] -cne 'false') {
        throw "$key must be true or false"
    }
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
    foreach ($key in @('MOODLE_BASE_URL', 'MOODLE_TOKEN', 'MOODLE_COURSE_IDS', 'MOODLE_DEMO_JAVA_COURSE', 'MOODLE_DEMO_DATA_GROUP',
            'MOODLE_DEMO_TEACH_COURSE', 'MOODLE_DEMO_WEB_COURSE', 'MOODLE_DEMO_EMPTY_COURSE')) {
        $values[$key] = ''
    }
}
Write-EnvironmentFile $envFilePath $values
Protect-SecretFile $envFilePath
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
    # Консоль Keycloak (realm master) защищена так же, как вход в CRM: временная блокировка после 10 неудачных входов
    # (5 минут, растёт до 15, постоянной блокировки нет) и журнал событий входа на год.
    Invoke-Keycloak @(
        'update', 'realms/master',
        '-s', 'bruteForceProtected=true', '-s', 'permanentLockout=false', '-s', 'failureFactor=10',
        '-s', 'waitIncrementSeconds=300', '-s', 'maxFailureWaitSeconds=900', '-s', 'maxDeltaTimeSeconds=43200',
        '-s', 'eventsEnabled=true', '-s', 'eventsExpiration=31536000'
    ) | Out-Null
    # Bootstrap входит через admin-cli realm master, поэтому вход паролем в обход формы у admin-cli realm rtk-crm не нужен.
    $adminCli = @(Invoke-Keycloak @('get', 'clients', '-r', 'rtk-crm', '-q', 'clientId=admin-cli') | ConvertFrom-Json)
    if ($adminCli.Count -ne 1) {
        throw 'admin-cli client is not unique'
    }
    Invoke-Keycloak @('update', "clients/$($adminCli[0].id)", '-r', 'rtk-crm', '-s', 'directAccessGrantsEnabled=false') | Out-Null
    Set-PrivilegedSecondFactor
    Set-CrmRealmRoles
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
            'pkce.code.challenge.method' = 'S256'
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
    Set-CrmRolesMapper $client[0].id
    $syncClient = @(Invoke-Keycloak @('get', 'clients', '-r', 'rtk-crm', '-q', 'clientId=crm-account-sync') | ConvertFrom-Json)
    $syncClientDefinition = @{
        clientId = 'crm-account-sync'
        enabled = $true
        protocol = 'openid-connect'
        publicClient = $false
        standardFlowEnabled = $false
        implicitFlowEnabled = $false
        directAccessGrantsEnabled = $false
        serviceAccountsEnabled = $true
        secret = $values['CRM_ACCOUNT_SYNC_CLIENT_SECRET']
    } | ConvertTo-Json -Compress
    if ($syncClient.Count -eq 0) {
        $syncClientDefinition | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh create clients -r rtk-crm -f -
        if ($LASTEXITCODE -ne 0) {
            throw 'Cannot create the CRM account sync client'
        }
        $syncClient = @(Invoke-Keycloak @('get', 'clients', '-r', 'rtk-crm', '-q', 'clientId=crm-account-sync') | ConvertFrom-Json)
    }
    if ($syncClient.Count -ne 1) {
        throw 'CRM account sync client is not unique'
    }
    $syncClientDefinition | & docker compose --env-file $envFilePath exec -T keycloak /opt/keycloak/bin/kcadm.sh update "clients/$($syncClient[0].id)" -r rtk-crm -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot update the CRM account sync client'
    }
    Invoke-Keycloak @(
        'add-roles', '-r', 'rtk-crm',
        '--uusername', 'service-account-crm-account-sync',
        '--cclientid', 'realm-management',
        '--rolename', 'manage-users',
        '--rolename', 'view-events'
    ) | Out-Null
    Set-McpClient
    $issuer = "$($values['PUBLIC_ORIGIN'])/idp/realms/rtk-crm"
    $identityFile = Join-Path $projectRoot '.demo-identities.yml'
    $subjects = @{}
    function ConvertTo-IdentityYaml([string]$Key, [string]$DisplayName, [string]$Role, [string]$TeamKey, [bool]$EnrolmentOperator = $false) {
        $lines = @(
            "      - key: $(ConvertTo-YamlLiteral $Key)",
            "        issuer: $(ConvertTo-YamlLiteral $issuer)",
            "        subject: $(ConvertTo-YamlLiteral $subjects[$Key])",
            "        display-name: $(ConvertTo-YamlLiteral $DisplayName)",
            "        role: $Role"
        )
        if ($TeamKey) {
            $lines += "        team-key: $(ConvertTo-YamlLiteral $TeamKey)"
        }
        if ($EnrolmentOperator) {
            $lines += "        enrolment-operator: true"
        }
        if ($Role -eq 'PARTNER') {
            $lines += "        organization: 'Университет А'"
            $lines += "        contact: 'Александра Демонстрационная'"
        }
        return $lines
    }
    if ($values['DEMO_DATA'] -eq 'true') {
        $demoIdentities = @(
            @('kam-a', 'КАМ А', 'USER', 'team-a'),
            @('kam-b', 'КАМ Б', 'USER', 'team-b'),
            @('kam-c', 'КАМ В', 'USER', 'team-a'),
            @('kam-d', 'КАМ Г', 'USER', 'team-a'),
            @('leader', 'Руководитель', 'LEADER', 'team-a'),
            @('leader-b', 'Руководитель Б', 'LEADER', 'team-b'),
            @('admin', 'Администратор', 'ADMIN', 'team-a'),
            @('management', 'Руководство', 'MANAGEMENT', $null),
            @('enrol', 'Менеджер учебного процесса', 'USER', 'open-enrolment', $true),
            @('partner', 'Представитель вуза', 'PARTNER', '')
        )
        $controlIdentities = @(
            @('kam-e', 'КАМ Д', 'USER', 'team-b'),
            @('kam-f', 'КАМ Е', 'USER', 'team-c'),
            @('kam-g', 'КАМ Ж', 'USER', 'team-c'),
            @('kam-h', 'КАМ З', 'USER', 'team-c'),
            @('leader-c', 'Руководитель В', 'LEADER', 'team-c')
        )
        $spareAccounts = @(
            @('unprofiled', 'Без профиля CRM'),
            @('unprofiled-2', 'Без профиля CRM, запасная')
        )
        foreach ($account in $demoIdentities + $controlIdentities + $spareAccounts) {
            $subjects[$account[0]] = Ensure-KeycloakUser $account[0] $account[1] $values['DEMO_USER_PASSWORD']
        }
        foreach ($identity in $demoIdentities + $controlIdentities) {
            if ($identity[2] -eq 'ADMIN' -or ($identity.Count -gt 4 -and $identity[4])) {
                Set-SecondFactorRequired $identity[0]
            }
            Set-CrmRole $identity[0] $identity[2]
        }
        $identityYaml = @(
            'app:',
            '  demo-bootstrap:',
            '    demo-data: true',
            '    teams:',
            "      - key: 'team-a'",
            "        name: 'Команда А'",
            "      - key: 'team-b'",
            "        name: 'Команда Б'",
            "      - key: 'open-enrolment'",
            "        name: 'Открытый набор'",
            "      - key: 'team-c'",
            "        name: 'Команда В'",
            '    identities:'
        )
        foreach ($identity in $demoIdentities + $controlIdentities) {
            $identityYaml += ConvertTo-IdentityYaml $identity[0] $identity[1] $identity[2] $identity[3] ($identity.Count -gt 4 -and $identity[4])
        }
        $identityYaml += @(
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
            "        team-key: 'team-a'",
            "      - name: 'Школа № 1 (демо)'",
            "        type: SCHOOL",
            "        team-key: 'team-a'",
            "        owner-key: 'kam-d'",
            "      - name: 'Колледж связи (демо)'",
            "        type: COLLEGE",
            "        team-key: 'team-b'",
            "        owner-key: 'kam-b'",
            "      - name: 'Политехнический университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-a'",
            "        owner-key: 'kam-a'",
            "      - name: 'Педагогический университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-a'",
            "        owner-key: 'kam-c'",
            "      - name: 'Колледж информационных технологий (демо)'",
            "        type: COLLEGE",
            "        team-key: 'team-a'",
            "        owner-key: 'kam-c'",
            "      - name: 'Лицей № 2 (демо)'",
            "        type: SCHOOL",
            "        team-key: 'team-a'",
            "        owner-key: 'kam-d'",
            "      - name: 'Университет телекоммуникаций (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-b'",
            "        owner-key: 'kam-b'",
            "      - name: 'Аграрный университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-b'",
            "        owner-key: 'kam-e'",
            "      - name: 'Медицинский университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-b'",
            "        owner-key: 'kam-e'",
            "      - name: 'Колледж связи № 2 (демо)'",
            "        type: COLLEGE",
            "        team-key: 'team-b'",
            "        owner-key: 'kam-e'",
            "      - name: 'Горный университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-c'",
            "        owner-key: 'kam-f'",
            "      - name: 'Транспортный университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-c'",
            "        owner-key: 'kam-f'",
            "      - name: 'Экономический университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-c'",
            "        owner-key: 'kam-g'",
            "      - name: 'Колледж автоматики (демо)'",
            "        type: COLLEGE",
            "        team-key: 'team-c'",
            "        owner-key: 'kam-g'",
            "      - name: 'Классический университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-c'",
            "        owner-key: 'kam-h'",
            "      - name: 'Строительный университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-c'",
            "        owner-key: 'kam-h'",
            "      - name: 'Школа № 7 (демо)'",
            "        type: SCHOOL",
            "        team-key: 'team-c'",
            "        owner-key: 'kam-h'",
            "      - name: 'Институт без ответственного (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-c'",
            "      - name: 'Технологический университет (демо)'",
            "        type: UNIVERSITY",
            "        team-key: 'team-a'",
            "        owner-key: 'kam-a'"
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
            if (-not [string]::IsNullOrWhiteSpace($values['MOODLE_DEMO_TEACH_COURSE'])) {
                $identityYaml += @(
                    '      - kind: COURSE',
                    "        external-key: $(ConvertTo-YamlLiteral $values['MOODLE_DEMO_TEACH_COURSE'])",
                    "        organization: 'Университет А'",
                    "        program: 'Демо-программа: цифровой университет'",
                    '        run-kind: TEACHERS'
                )
            }
            if (-not [string]::IsNullOrWhiteSpace($values['MOODLE_DEMO_WEB_COURSE']) -and
                -not [string]::IsNullOrWhiteSpace($values['MOODLE_DEMO_EMPTY_COURSE'])) {
                $identityYaml += @(
                    '      - kind: COURSE',
                    "        external-key: $(ConvertTo-YamlLiteral $values['MOODLE_DEMO_WEB_COURSE'])",
                    "        organization: 'Школа № 1 (демо)'",
                    "        program: 'Демо-программа: цифровой университет'",
                    '        run-started-days-ago: 60',
                    '        run-ends-in-days: 4',
                    '      - kind: COURSE',
                    "        external-key: $(ConvertTo-YamlLiteral $values['MOODLE_DEMO_EMPTY_COURSE'])",
                    "        organization: 'Школа № 1 (демо)'",
                    "        program: 'Демо-программа: анализ данных'",
                    '        run-started-days-ago: 30',
                    '        run-ends-in-days: 90'
                )
            }
        }
    }
    else {
        $subjects[$values['CRM_ADMIN_USERNAME']] = Ensure-CrmAdministrator
        Set-SecondFactorRequired $values['CRM_ADMIN_USERNAME']
        Set-CrmRole $values['CRM_ADMIN_USERNAME'] 'ADMIN'
        $identityYaml = @(
            'app:',
            '  demo-bootstrap:',
            '    demo-data: false',
            '    identities:'
        ) + (ConvertTo-IdentityYaml $values['CRM_ADMIN_USERNAME'] $values['CRM_ADMIN_DISPLAY_NAME'] 'ADMIN' '')
    }
    Set-Content -LiteralPath $identityFile -Value $identityYaml -Encoding utf8NoBOM
    Protect-SecretFile $identityFile
    if ($values['SITE_BASE_URL'] -eq $siteFixtureUrl) {
        & docker compose --env-file $envFilePath --profile demo up -d --wait site-fixture
        if ($LASTEXITCODE -ne 0) {
            throw 'Cannot start the demo site fixture'
        }
    }
    if ($values['LABOR_MARKET_BASE_URL'] -eq $laborMarketFixtureUrl) {
        & docker compose --env-file $envFilePath --profile demo up -d --wait labor-market-fixture
        if ($LASTEXITCODE -ne 0) {
            throw 'Cannot start the demo labor market fixture'
        }
    }
    if ($values['CRM_BASE_URL'] -eq $crmStubUrl) {
        & docker compose --env-file $envFilePath --profile demo up -d --wait crm-stub
        if ($LASTEXITCODE -ne 0) {
            throw 'Cannot start the demo customer CRM stub'
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
    & docker compose --env-file $envFilePath @moodleFiles --profile demo run --rm -T --build backend-bootstrap
    if ($LASTEXITCODE -ne 0) {
        throw 'CRM demo bootstrap failed'
    }
    if ($values['DEMO_DATA'] -eq 'false') {
        Write-Output "CRM bootstrap completed without demo data. Administrator $($values['CRM_ADMIN_USERNAME']): initial password CRM_ADMIN_PASSWORD in $envFilePath, it must be changed at first sign-in"
    }
    elseif ($values['DEMO_ACCOUNTS_SECURED'] -eq 'true') {
        Write-Output 'Demo bootstrap completed. Demo accounts stay secured: their passwords and disabled state were not changed'
    }
    else {
        Write-Output "Demo bootstrap completed. Local credentials are in $envFilePath"
    }
    if ($values['MOODLE_BASE_URL'] -eq $moodleDemoUrl) {
        Write-Output "Demo Moodle: http://localhost:8082, administrator and read-only jury (crm-jury) credentials are in $(Join-Path $projectRoot 'infra/moodle/.env.local')"
    }
}
finally {
    Pop-Location
}
