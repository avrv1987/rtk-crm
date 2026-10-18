#!/usr/bin/env bash
set -Eeuo pipefail
export MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:-/opt/}"

trap 'printf "bootstrap-demo: failed at line %s\n" "$LINENO" >&2' ERR

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${1:-$project_root/.env.local}
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")

fail() {
    printf 'bootstrap-demo: %s\n' "$1" >&2
    exit 1
}

new_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 32
    else
        head -c 32 /dev/urandom | base64
    fi | tr '+/' '-_' | tr -d '=\r\n'
}

is_blank() {
    [[ -z ${1//[[:space:]]/} ]]
}

json_string() {
    printf '"%s"' "$(printf '%s' "$1" | sed 's/[\\"]/\\&/g')"
}

join_by() {
    local separator=$1 result=$2
    shift 2
    for item in "$@"; do
        result+=$separator$item
    done
    printf '%s' "$result"
}

yaml_literal() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/''/g")"
}

declare -A values=()
keys=()

set_value() {
    [[ -n ${values[$1]+set} ]] || keys+=("$1")
    values[$1]=$2
}

load_env_file() {
    [[ -f $env_file ]] || return 0
    local line
    while IFS= read -r line || [[ -n $line ]]; do
        line=${line%$'\r'}
        if [[ $line =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
            set_value "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
        fi
    done < "$env_file"
}

write_env_file() {
    (
        umask 077
        for key in "${keys[@]}"; do
            printf '%s=%s\n' "$key" "${values[$key]}"
        done > "$env_file"
    )
    chmod 600 "$env_file"
}

load_env_file
for key in DEMO_DATA DEMO_LMS; do
    if [[ -n ${!key-} ]]; then
        set_value "$key" "${!key}"
    fi
done
if is_blank "${values[DEMO_DATA]-}"; then
    set_value DEMO_DATA true
fi
[[ ${values[DEMO_DATA]} == true || ${values[DEMO_DATA]} == false ]] || fail 'DEMO_DATA must be true or false'
if is_blank "${values[MODULE_ENROLMENT_ENABLED]-}" && ! is_blank "${values[ENROLMENT_ENABLED]-}"; then
    set_value MODULE_ENROLMENT_ENABLED "${values[ENROLMENT_ENABLED]}"
fi
site_fixture_url=http://site-fixture:8080
labor_market_fixture_url=http://labor-market-fixture:8080
crm_stub_url=http://crm-stub:8080

defaults=(
    POSTGRES_SUPERUSER=postgres
    CRM_DB_NAME=rtk_crm
    CRM_DB_USER=crm
    KEYCLOAK_DB_NAME=keycloak
    KEYCLOAK_DB_USER=keycloak
    KEYCLOAK_ADMIN_USERNAME=bootstrap-admin
    PUBLIC_ORIGIN=http://rtk.localhost:8081
    'SOURCES_SYNC_CRON=0 0 * * * *'
    ENROLMENT_ACTIVE_KEY_VERSION=v1
    MFA_REQUIRED_FOR_PRIVILEGED=false
    MCP_ENABLED=false
    MCP_REDIRECT_URIS=http://127.0.0.1:8976/callback,http://localhost:6274/oauth/callback,http://localhost:6274/oauth/callback/debug
)
secrets=(
    POSTGRES_SUPERUSER_PASSWORD
    CRM_DB_PASSWORD
    KEYCLOAK_DB_PASSWORD
    KEYCLOAK_ADMIN_PASSWORD
    CRM_OIDC_CLIENT_SECRET
    CRM_ACCOUNT_SYNC_CLIENT_SECRET
    ENROLMENT_KEYS_V1
    ENROLMENT_FINGERPRINT_KEY
    SOURCES_SETTINGS_KEY
    BACKUP_ENCRYPTION_KEY
)
if [[ ${values[DEMO_DATA]} == true ]]; then
    defaults+=(SITE_BASE_URL=$site_fixture_url LABOR_MARKET_BASE_URL=$labor_market_fixture_url CRM_BASE_URL=$crm_stub_url DEMO_LMS=true MODULE_ENROLMENT_ENABLED=true MODULE_PARTNER_ENABLED=true COMPOSE_PROFILES=security,demo)
    secrets+=(DEMO_USER_PASSWORD SITE_TOKEN LABOR_MARKET_TOKEN CRM_TOKEN)
else
    [[ ${values[DEMO_LMS]-} != true ]] || fail 'DEMO_LMS=true requires DEMO_DATA=true'
    [[ ${values[SITE_BASE_URL]-} != "$site_fixture_url" ]] || set_value SITE_BASE_URL ''
    [[ ${values[LABOR_MARKET_BASE_URL]-} != "$labor_market_fixture_url" ]] || set_value LABOR_MARKET_BASE_URL ''
    [[ ${values[CRM_BASE_URL]-} != "$crm_stub_url" ]] || set_value CRM_BASE_URL ''
    defaults+=(DEMO_LMS=false MODULE_ENROLMENT_ENABLED=true MODULE_PARTNER_ENABLED=true COMPOSE_PROFILES=security CRM_ADMIN_USERNAME=admin 'CRM_ADMIN_DISPLAY_NAME=Администратор')
    defaults+=(CRM_ADMIN_EMAIL=admin@rtk-crm.local)
    secrets+=(CRM_ADMIN_PASSWORD)
fi
for pair in "${defaults[@]}"; do
    if is_blank "${values[${pair%%=*}]-}"; then
        set_value "${pair%%=*}" "${pair#*=}"
    fi
done
for key in "${secrets[@]}"; do
    if is_blank "${values[$key]-}"; then
        set_value "$key" "$(new_secret)"
    fi
done
[[ ${values[DEMO_LMS]} == true || ${values[DEMO_LMS]} == false ]] || fail 'DEMO_LMS must be true or false'
[[ ${values[MFA_REQUIRED_FOR_PRIVILEGED]} == true || ${values[MFA_REQUIRED_FOR_PRIVILEGED]} == false ]] \
    || fail 'MFA_REQUIRED_FOR_PRIVILEGED must be true or false'
for key in MODULE_ENROLMENT_ENABLED MODULE_PARTNER_ENABLED MCP_ENABLED; do
    [[ ${values[$key]} == true || ${values[$key]} == false ]] || fail "$key must be true or false"
done
write_env_file

moodle_demo_url=http://moodle:8080
moodle_demo_keys=(MOODLE_BASE_URL MOODLE_TOKEN MOODLE_COURSE_IDS MOODLE_DEMO_JAVA_COURSE MOODLE_DEMO_DATA_GROUP
    MOODLE_DEMO_TEACH_COURSE MOODLE_DEMO_WEB_COURSE MOODLE_DEMO_EMPTY_COURSE)
if [[ ${values[DEMO_LMS]} == true ]]; then
    if is_blank "${values[MOODLE_BASE_URL]-}" || [[ ${values[MOODLE_BASE_URL]} == "$moodle_demo_url" ]]; then
        MOODLE_CRM_URL=$moodle_demo_url bash "$project_root/scripts/moodle-demo.sh" "$env_file"
        load_env_file
    fi
elif [[ ${values[MOODLE_BASE_URL]-} == "$moodle_demo_url" ]]; then
    for key in "${moodle_demo_keys[@]}"; do
        set_value "$key" ''
    done
    write_env_file
fi
for key in "${keys[@]}"; do
    export "$key=${values[$key]}"
done

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

kcadm() {
    compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"
}

single_id() {
    [[ -n $2 && $2 != *$'\n'* ]] || fail "$1 is not unique"
    printf '%s' "$2"
}

declare -A subjects=()

find_user() {
    kcadm get users -r rtk-crm -q "username=$1" -q exact=true --fields id --format csv --noquotes
}

user_definition() {
    local email=
    [[ -z $4 ]] || email=",\"email\":$(json_string "$4")"
    printf '{"username":%s,"firstName":%s,"lastName":%s%s,"enabled":%s,"emailVerified":true,"requiredActions":[]}' \
        "$(json_string "$1")" "$(json_string "$2")" "$(json_string "$3")" "$email" "$5"
}

set_password() {
    printf '{"type":"password","value":%s,"temporary":%s}' "$(json_string "$2")" "$3" \
        | kcadm update "users/$1/reset-password" -r rtk-crm -f -
}

ensure_user() {
    local username=$1 first_name=$2 definition id
    id=$(find_user "$username")
    if [[ ${values[DEMO_ACCOUNTS_SECURED]-} == true ]]; then
        if [[ -z $id ]]; then
            id=$(user_definition "$username" "$first_name" Demo "$username@demo.rtk.local" false | kcadm create users -r rtk-crm -f - -i)
        fi
    else
        definition=$(user_definition "$username" "$first_name" Demo "$username@demo.rtk.local" true)
        if [[ -z $id ]]; then
            id=$(printf '%s' "$definition" | kcadm create users -r rtk-crm -f - -i)
        fi
        id=$(single_id "Keycloak user $username" "$id")
        set_password "$id" "${values[DEMO_USER_PASSWORD]}" false
        printf '%s' "$definition" | kcadm update "users/$id" -r rtk-crm -f -
    fi
    subjects[$username]=$(single_id "Keycloak user $username" "$id")
}

ensure_administrator() {
    local username=${values[CRM_ADMIN_USERNAME]} email=${values[CRM_ADMIN_EMAIL]} id
    id=$(find_user "$username")
    if [[ -z $id ]]; then
        id=$(user_definition "$username" "${values[CRM_ADMIN_DISPLAY_NAME]}" CRM "$email" true | kcadm create users -r rtk-crm -f - -i)
        id=$(single_id "Keycloak user $username" "$id")
        set_password "$id" "${values[CRM_ADMIN_PASSWORD]}" true
    fi
    id=$(single_id "Keycloak user $username" "$id")
    if [[ -z $(kcadm get "users/$id" -r rtk-crm --fields email --format csv --noquotes) ]]; then
        printf '{"email":%s,"emailVerified":true}' "$(json_string "$email")" | kcadm update "users/$id" -r rtk-crm -f -
    fi
    subjects[$username]=$id
}

privileged_role=crm-privileged
mfa_flow=crm-browser

flow_id() {
    kcadm get authentication/flows -r rtk-crm --fields id,alias --format csv --noquotes \
        | awk -F, -v alias="$1" '$2 == alias { print $1 } $1 == alias { print $2 }'
}

add_execution() {
    printf '{"parentFlow":"%s","authenticator":"%s","requirement":"%s","priority":%s,"authenticatorFlow":false}' "$@" \
        | kcadm create authentication/executions -r rtk-crm -f - -i
}

add_subflow() {
    local id
    id=$(printf '{"alias":"%s","providerId":"basic-flow","topLevel":false,"builtIn":false}' "$2" \
        | kcadm create authentication/flows -r rtk-crm -f - -i)
    printf '{"parentFlow":"%s","flowId":"%s","requirement":"%s","priority":%s,"authenticatorFlow":true}' "$1" "$id" "$3" "$4" \
        | kcadm create authentication/executions -r rtk-crm -f - -i >/dev/null
    printf '%s' "$id"
}

clear_pending_totp() {
    local id user actions
    for id in $(kcadm get "roles/$privileged_role/users" -r rtk-crm -q max=10000 --fields id --format csv --noquotes); do
        user=$(kcadm get "users/$id" -r rtk-crm --fields requiredActions)
        [[ $user == *'"CONFIGURE_TOTP"'* ]] || continue
        actions=$(grep -o '"[A-Z_]*"' <<< "$user" | grep -vx '"CONFIGURE_TOTP"' | paste -sd, - || true)
        printf '{"requiredActions":[%s]}' "$actions" | kcadm update "users/$id" -r rtk-crm -f -
    done
}

configure_privileged_mfa() {
    local top forms otp condition
    if ! kcadm get "roles/$privileged_role" -r rtk-crm >/dev/null 2>&1; then
        kcadm create roles -r rtk-crm -s "name=$privileged_role" -s 'description=CRM: second factor is required at sign-in'
    fi
    kcadm update realms/rtk-crm -s browserFlow=browser
    top=$(flow_id "$mfa_flow")
    [[ -z $top ]] || kcadm delete "authentication/flows/$top" -r rtk-crm
    if [[ ${values[MFA_REQUIRED_FOR_PRIVILEGED]} != true ]]; then
        clear_pending_totp
        return
    fi
    top=$(printf '{"alias":"%s","description":"Browser sign-in, OTP for %s","providerId":"basic-flow","topLevel":true,"builtIn":false}' \
        "$mfa_flow" "$privileged_role" | kcadm create authentication/flows -r rtk-crm -f - -i)
    add_execution "$top" auth-cookie ALTERNATIVE 10 >/dev/null
    forms=$(add_subflow "$top" "$mfa_flow forms" ALTERNATIVE 20)
    add_execution "$forms" auth-username-password-form REQUIRED 10 >/dev/null
    otp=$(add_subflow "$forms" "$mfa_flow privileged OTP" CONDITIONAL 20)
    condition=$(add_execution "$otp" conditional-user-role REQUIRED 10)
    printf '{"alias":"%s role","config":{"condUserRole":"%s","negate":"false"}}' "$mfa_flow" "$privileged_role" \
        | kcadm create "authentication/executions/$condition/config" -r rtk-crm -f -
    add_execution "$otp" auth-otp-form REQUIRED 20 >/dev/null
    kcadm update realms/rtk-crm -s "browserFlow=$mfa_flow"
}

require_second_factor() {
    kcadm add-roles -r rtk-crm --uusername "$1" --rolename "$privileged_role"
}

crm_roles=(crm-user crm-leader crm-admin crm-management crm-partner)
roles_mapper_name='crm realm roles'

ensure_crm_roles() {
    local role
    for role in "${crm_roles[@]}"; do
        if ! kcadm get "roles/$role" -r rtk-crm >/dev/null 2>&1; then
            kcadm create roles -r rtk-crm -s "name=$role" -s 'description=CRM role'
        fi
    done
    if ! kcadm get roles/crm-admin/composites -r rtk-crm --fields name --format csv --noquotes | grep -qx "$privileged_role"; then
        kcadm add-roles -r rtk-crm --rname crm-admin --rolename "$privileged_role"
    fi
}

ensure_roles_mapper() {
    local mapper mapper_id
    mapper=$(printf '{"name":"%s","protocol":"openid-connect","protocolMapper":"oidc-usermodel-realm-role-mapper","config":{"claim.name":"realm_access.roles","multivalued":"true","jsonType.label":"String","id.token.claim":"true","access.token.claim":"false","userinfo.token.claim":"false","introspection.token.claim":"false"}}' \
        "$roles_mapper_name")
    mapper_id=$(kcadm get "clients/$1/protocol-mappers/models" -r rtk-crm --fields id,name --format csv --noquotes \
        | awk -F, -v name="$roles_mapper_name" '$2 == name { print $1 } $1 == name { print $2 }')
    if [[ -z $mapper_id ]]; then
        printf '%s' "$mapper" | kcadm create "clients/$1/protocol-mappers/models" -r rtk-crm -f -
    else
        printf '{"id":"%s",%s' "$mapper_id" "${mapper#\{}" | kcadm update "clients/$1/protocol-mappers/models/$mapper_id" -r rtk-crm -f -
    fi
}

assign_crm_role() {
    kcadm add-roles -r rtk-crm --uusername "$1" --rolename "crm-${2,,}"
}

compose up -d --wait postgres keycloak
compose exec -T keycloak bash -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null
kcadm update realms/rtk-crm -f /opt/keycloak/data/import/rtk-crm-realm.json
# Консоль Keycloak (realm master) защищена так же, как вход в CRM: временная блокировка после 10 неудачных входов
# (5 минут, растёт до 15, постоянной блокировки нет) и журнал событий входа на год.
kcadm update realms/master -s bruteForceProtected=true -s permanentLockout=false -s failureFactor=10 \
    -s waitIncrementSeconds=300 -s maxFailureWaitSeconds=900 -s maxDeltaTimeSeconds=43200 \
    -s eventsEnabled=true -s eventsExpiration=31536000
# Bootstrap входит через admin-cli realm master, поэтому вход паролем в обход формы у admin-cli realm rtk-crm не нужен.
admin_cli_id=$(single_id 'admin-cli client' "$(kcadm get clients -r rtk-crm -q clientId=admin-cli --fields id --format csv --noquotes)")
kcadm update "clients/$admin_cli_id" -r rtk-crm -s directAccessGrantsEnabled=false
configure_privileged_mfa
ensure_crm_roles

public_origin=${values[PUBLIC_ORIGIN]}
web_origins=("$public_origin")
if [[ $public_origin == http://rtk.localhost:8081 ]]; then
    web_origins+=(http://localhost:8081)
fi
redirect_uris=()
origins=()
post_logout_uris=()
for origin in "${web_origins[@]}"; do
    redirect_uris+=("$(json_string "$origin/api/auth/callback/keycloak")")
    origins+=("$(json_string "$origin")")
    post_logout_uris+=("$origin" "$origin/*")
done
client_definition=$(printf '{"clientId":"crm-bff","enabled":true,"protocol":"openid-connect","publicClient":false,"standardFlowEnabled":true,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":false,"secret":%s,"redirectUris":[%s],"webOrigins":[%s],"attributes":{"post.logout.redirect.uris":%s,"pkce.code.challenge.method":"S256"}}' \
    "$(json_string "${values[CRM_OIDC_CLIENT_SECRET]}")" \
    "$(join_by , "${redirect_uris[@]}")" \
    "$(join_by , "${origins[@]}")" \
    "$(json_string "$(join_by '##' "${post_logout_uris[@]}")")")
client_id=$(kcadm get clients -r rtk-crm -q clientId=crm-bff --fields id --format csv --noquotes)
if [[ -z $client_id ]]; then
    client_id=$(printf '%s' "$client_definition" | kcadm create clients -r rtk-crm -f - -i)
fi
client_id=$(single_id 'CRM OIDC client' "$client_id")
printf '%s' "$client_definition" | kcadm update "clients/$client_id" -r rtk-crm -f -
ensure_roles_mapper "$client_id"

sync_client_definition=$(printf '{"clientId":"crm-account-sync","enabled":true,"protocol":"openid-connect","publicClient":false,"standardFlowEnabled":false,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":true,"secret":%s}' \
    "$(json_string "${values[CRM_ACCOUNT_SYNC_CLIENT_SECRET]}")")
sync_client_id=$(kcadm get clients -r rtk-crm -q clientId=crm-account-sync --fields id --format csv --noquotes)
if [[ -z $sync_client_id ]]; then
    sync_client_id=$(printf '%s' "$sync_client_definition" | kcadm create clients -r rtk-crm -f - -i)
fi
sync_client_id=$(single_id 'CRM account sync client' "$sync_client_id")
printf '%s' "$sync_client_definition" | kcadm update "clients/$sync_client_id" -r rtk-crm -f -
# view-events: CRM переносит неудачные входы (события LOGIN_ERROR) в журнал. Сохранение событий realm включает
# rtk-crm-realm.json (eventsEnabled), который применяется выше при каждом запуске.
kcadm add-roles -r rtk-crm --uusername service-account-crm-account-sync --cclientid realm-management --rolename manage-users \
    --rolename view-events

# MCP для ИИ-агентов (docs/mcp.md). Клиентский scope crm-mcp кладёт в access token aud=crm-mcp и роли CRM; публичный
# клиент crm-mcp-client входит по PKCE с экраном согласия, без offline_access. Регистрации клиентов «на лету» (DCR) нет:
# адреса возврата клиентов перечисляются в MCP_REDIRECT_URIS. При MCP_ENABLED=false уже заведённый клиент отключается,
# а realm не пересоздаётся ни в каком случае.
ensure_scope_mapper() {
    local mapper_id definition
    definition=$(printf '{"name":"%s","protocol":"openid-connect",%s' "$2" "${3#\{}")
    mapper_id=$(kcadm get "client-scopes/$1/protocol-mappers/models" -r rtk-crm --fields id,name --format csv --noquotes \
        | awk -F, -v name="$2" '$2 == name { print $1 } $1 == name { print $2 }')
    if [[ -z $mapper_id ]]; then
        printf '%s' "$definition" | kcadm create "client-scopes/$1/protocol-mappers/models" -r rtk-crm -f -
    else
        printf '{"id":"%s",%s' "$mapper_id" "${definition#\{}" | kcadm update "client-scopes/$1/protocol-mappers/models/$mapper_id" -r rtk-crm -f -
    fi
}

scope_id_by_name() {
    kcadm get client-scopes -r rtk-crm --fields id,name --format csv --noquotes | awk -F, -v name="$1" '$2 == name { print $1 }'
}

configure_mcp() {
    local client_id scope_id write_scope_id offline_id definition uri
    local redirect_uris=()
    client_id=$(kcadm get clients -r rtk-crm -q clientId=crm-mcp-client --fields id --format csv --noquotes)
    if [[ ${values[MCP_ENABLED]} != true ]]; then
        [[ -z $client_id ]] || kcadm update "clients/$(single_id 'MCP client' "$client_id")" -r rtk-crm -s enabled=false
        return 0
    fi
    scope_id=$(scope_id_by_name crm-mcp)
    if [[ -z $scope_id ]]; then
        scope_id=$(printf '%s' '{"name":"crm-mcp","protocol":"openid-connect","attributes":{"include.in.token.scope":"true","display.on.consent.screen":"true","consent.screen.text":"Доступ ИИ-агента к CRM от вашего имени, только чтение"}}' \
            | kcadm create client-scopes -r rtk-crm -f - -i)
    fi
    scope_id=$(single_id 'MCP client scope' "$scope_id")
    ensure_scope_mapper "$scope_id" 'crm-mcp audience' \
        '{"protocolMapper":"oidc-audience-mapper","config":{"included.custom.audience":"crm-mcp","access.token.claim":"true","id.token.claim":"false","introspection.token.claim":"true"}}'
    ensure_scope_mapper "$scope_id" 'crm realm roles' \
        '{"protocolMapper":"oidc-usermodel-realm-role-mapper","config":{"claim.name":"realm_access.roles","multivalued":"true","jsonType.label":"String","id.token.claim":"false","access.token.claim":"true","userinfo.token.claim":"false","introspection.token.claim":"true"}}'
    IFS=',' read -r -a uris <<< "${values[MCP_REDIRECT_URIS]}"
    for uri in "${uris[@]}"; do
        [[ -z $uri ]] || redirect_uris+=("$(json_string "$uri")")
    done
    definition=$(printf '{"clientId":"crm-mcp-client","name":"MCP: ИИ-агенты","enabled":true,"protocol":"openid-connect","publicClient":true,"standardFlowEnabled":true,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":false,"consentRequired":true,"redirectUris":[%s],"webOrigins":["+"],"attributes":{"pkce.code.challenge.method":"S256","access.token.lifespan":"300","oauth2.device.authorization.grant.enabled":"false"}}' \
        "$(join_by , "${redirect_uris[@]}")")
    if [[ -z $client_id ]]; then
        client_id=$(printf '%s' "$definition" | kcadm create clients -r rtk-crm -f - -i)
    fi
    client_id=$(single_id 'MCP client' "$client_id")
    printf '%s' "$definition" | kcadm update "clients/$client_id" -r rtk-crm -f -
    kcadm update "clients/$client_id/default-client-scopes/$scope_id" -r rtk-crm
    # Scope записи — необязательный: клиент получает его, только если явно запросил, а сотрудник согласился. Он даёт
    # лишь подготовку плана; записывает план сам сотрудник на экране CRM. Без него у клиента остаётся чтение.
    write_scope_id=$(scope_id_by_name crm-mcp.write)
    if [[ -z $write_scope_id ]]; then
        write_scope_id=$(printf '%s' '{"name":"crm-mcp.write","protocol":"openid-connect","attributes":{"include.in.token.scope":"true","display.on.consent.screen":"true","consent.screen.text":"Подготовка записей ИИ-агентом; записываете вы сами на экране CRM"}}'             | kcadm create client-scopes -r rtk-crm -f - -i)
    fi
    write_scope_id=$(single_id 'MCP write scope' "$write_scope_id")
    kcadm update "clients/$client_id/optional-client-scopes/$write_scope_id" -r rtk-crm
    offline_id=$(scope_id_by_name offline_access)
    if [[ -n $offline_id ]] && kcadm get "clients/$client_id/optional-client-scopes" -r rtk-crm --fields id --format csv --noquotes \
        | grep -qx "$offline_id"; then
        kcadm delete "clients/$client_id/optional-client-scopes/$offline_id" -r rtk-crm
    fi
}
configure_mcp

demo_identities=(
    'kam-a|КАМ А|USER|team-a'
    'kam-b|КАМ Б|USER|team-b'
    'kam-c|КАМ В|USER|team-a'
    'kam-d|КАМ Г|USER|team-a'
    'leader|Руководитель|LEADER|team-a'
    'leader-b|Руководитель Б|LEADER|team-b'
    'admin|Администратор|ADMIN|team-a'
    'management|Руководство|MANAGEMENT'
    'enrol|Оператор зачисления|USER|open-enrolment|operator'
    'partner|Представитель вуза|PARTNER|'
)
control_identities=(
    'kam-e|КАМ Д|USER|team-b'
    'kam-f|КАМ Е|USER|team-c'
    'kam-g|КАМ Ж|USER|team-c'
    'kam-h|КАМ З|USER|team-c'
    'leader-c|Руководитель В|LEADER|team-c'
)
spare_accounts=(
    'unprofiled|Без профиля CRM'
    'unprofiled-2|Без профиля CRM, запасная'
)
issuer=$(yaml_literal "$public_origin/idp/realms/rtk-crm")
identity_file=$project_root/.demo-identities.yml

identity_yaml() {
    printf '      - key: %s\n        issuer: %s\n        subject: %s\n        display-name: %s\n        role: %s\n' \
        "$(yaml_literal "$1")" "$issuer" "$(yaml_literal "${subjects[$1]}")" "$(yaml_literal "$2")" "$3"
    [[ -z ${4-} ]] || printf '        team-key: %s\n' "$(yaml_literal "$4")"
    [[ ${5-} != operator ]] || printf '        enrolment-operator: true\n'
    [[ $3 != PARTNER ]] || printf "        organization: 'Университет А'\n        contact: 'Александра Демонстрационная'\n"
}

if [[ ${values[DEMO_DATA]} == true ]]; then
    for entry in "${demo_identities[@]}" "${control_identities[@]}" "${spare_accounts[@]}"; do
        IFS='|' read -r username display_name _ <<< "$entry"
        ensure_user "$username" "$display_name"
    done
    for entry in "${demo_identities[@]}" "${control_identities[@]}"; do
        IFS='|' read -r username _ role _ flag <<< "$entry"
        [[ $role != ADMIN && $flag != operator ]] || require_second_factor "$username"
        assign_crm_role "$username" "$role"
    done
    {
        cat <<'YAML'
app:
  demo-bootstrap:
    demo-data: true
    teams:
      - key: 'team-a'
        name: 'Команда А'
      - key: 'team-b'
        name: 'Команда Б'
      - key: 'open-enrolment'
        name: 'Открытый набор'
      - key: 'team-c'
        name: 'Команда В'
    identities:
YAML
        for entry in "${demo_identities[@]}" "${control_identities[@]}"; do
            IFS='|' read -r username display_name role team flag <<< "$entry"
            identity_yaml "$username" "$display_name" "$role" "$team" "$flag"
        done
        cat <<'YAML'
    organizations:
      - name: 'Университет А'
        type: UNIVERSITY
        team-key: 'team-a'
        owner-key: 'kam-a'
      - name: 'Университет Б'
        type: UNIVERSITY
        team-key: 'team-b'
        owner-key: 'kam-b'
      - name: 'Университет C — требует назначения'
        type: UNIVERSITY
        team-key: 'team-a'
      - name: 'Школа № 1 (демо)'
        type: SCHOOL
        team-key: 'team-a'
        owner-key: 'kam-d'
      - name: 'Колледж связи (демо)'
        type: COLLEGE
        team-key: 'team-b'
        owner-key: 'kam-b'
      - name: 'Политехнический университет (демо)'
        type: UNIVERSITY
        team-key: 'team-a'
        owner-key: 'kam-a'
      - name: 'Педагогический университет (демо)'
        type: UNIVERSITY
        team-key: 'team-a'
        owner-key: 'kam-c'
      - name: 'Колледж информационных технологий (демо)'
        type: COLLEGE
        team-key: 'team-a'
        owner-key: 'kam-c'
      - name: 'Лицей № 2 (демо)'
        type: SCHOOL
        team-key: 'team-a'
        owner-key: 'kam-d'
      - name: 'Университет телекоммуникаций (демо)'
        type: UNIVERSITY
        team-key: 'team-b'
        owner-key: 'kam-b'
      - name: 'Аграрный университет (демо)'
        type: UNIVERSITY
        team-key: 'team-b'
        owner-key: 'kam-e'
      - name: 'Медицинский университет (демо)'
        type: UNIVERSITY
        team-key: 'team-b'
        owner-key: 'kam-e'
      - name: 'Колледж связи № 2 (демо)'
        type: COLLEGE
        team-key: 'team-b'
        owner-key: 'kam-e'
      - name: 'Горный университет (демо)'
        type: UNIVERSITY
        team-key: 'team-c'
        owner-key: 'kam-f'
      - name: 'Транспортный университет (демо)'
        type: UNIVERSITY
        team-key: 'team-c'
        owner-key: 'kam-f'
      - name: 'Экономический университет (демо)'
        type: UNIVERSITY
        team-key: 'team-c'
        owner-key: 'kam-g'
      - name: 'Колледж автоматики (демо)'
        type: COLLEGE
        team-key: 'team-c'
        owner-key: 'kam-g'
      - name: 'Классический университет (демо)'
        type: UNIVERSITY
        team-key: 'team-c'
        owner-key: 'kam-h'
      - name: 'Строительный университет (демо)'
        type: UNIVERSITY
        team-key: 'team-c'
        owner-key: 'kam-h'
      - name: 'Школа № 7 (демо)'
        type: SCHOOL
        team-key: 'team-c'
        owner-key: 'kam-h'
      - name: 'Институт без ответственного (демо)'
        type: UNIVERSITY
        team-key: 'team-c'
      - name: 'Технологический университет (демо)'
        type: UNIVERSITY
        team-key: 'team-a'
        owner-key: 'kam-a'
YAML
        if [[ ${values[MOODLE_BASE_URL]-} == "$moodle_demo_url" ]] \
            && ! is_blank "${values[MOODLE_DEMO_JAVA_COURSE]-}" && ! is_blank "${values[MOODLE_DEMO_DATA_GROUP]-}"; then
            cat <<YAML
    learning-mappings:
      - kind: COURSE
        external-key: $(yaml_literal "${values[MOODLE_DEMO_JAVA_COURSE]}")
        organization: 'Университет А'
        program: 'Демо-программа: цифровой университет'
      - kind: GROUP
        external-key: $(yaml_literal "${values[MOODLE_DEMO_DATA_GROUP]}")
        organization: 'Университет Б'
        program: 'Демо-программа: анализ данных'
YAML
            if ! is_blank "${values[MOODLE_DEMO_TEACH_COURSE]-}"; then
                cat <<YAML
      - kind: COURSE
        external-key: $(yaml_literal "${values[MOODLE_DEMO_TEACH_COURSE]}")
        organization: 'Университет А'
        program: 'Демо-программа: цифровой университет'
        run-kind: TEACHERS
YAML
            fi
            if ! is_blank "${values[MOODLE_DEMO_WEB_COURSE]-}" && ! is_blank "${values[MOODLE_DEMO_EMPTY_COURSE]-}"; then
                cat <<YAML
      - kind: COURSE
        external-key: $(yaml_literal "${values[MOODLE_DEMO_WEB_COURSE]}")
        organization: 'Школа № 1 (демо)'
        program: 'Демо-программа: цифровой университет'
        run-started-days-ago: 60
        run-ends-in-days: 4
      - kind: COURSE
        external-key: $(yaml_literal "${values[MOODLE_DEMO_EMPTY_COURSE]}")
        organization: 'Школа № 1 (демо)'
        program: 'Демо-программа: анализ данных'
        run-started-days-ago: 30
        run-ends-in-days: 90
YAML
            fi
        fi
    } > "$identity_file"
else
    ensure_administrator
    require_second_factor "${values[CRM_ADMIN_USERNAME]}"
    assign_crm_role "${values[CRM_ADMIN_USERNAME]}" ADMIN
    {
        printf 'app:\n  demo-bootstrap:\n    demo-data: false\n    identities:\n'
        identity_yaml "${values[CRM_ADMIN_USERNAME]}" "${values[CRM_ADMIN_DISPLAY_NAME]}" ADMIN
    } > "$identity_file"
fi

if [[ ${values[SITE_BASE_URL]-} == "$site_fixture_url" ]]; then
    compose --profile demo up -d --wait site-fixture
fi
if [[ ${values[LABOR_MARKET_BASE_URL]-} == "$labor_market_fixture_url" ]]; then
    compose --profile demo up -d --wait labor-market-fixture
fi
if [[ ${values[CRM_BASE_URL]-} == "$crm_stub_url" ]]; then
    compose --profile demo up -d --wait crm-stub
fi
moodle_files=()
if [[ ${values[MOODLE_BASE_URL]-} == "$moodle_demo_url" ]]; then
    separator=:
    [[ $OSTYPE == msys* || $OSTYPE == cygwin* ]] && separator=';'
    IFS=${COMPOSE_PATH_SEPARATOR:-$separator} read -r -a compose_files <<< "${COMPOSE_FILE:-compose.yaml}"
    compose_files+=(infra/moodle/compose.crm.yaml)
    if ! is_blank "${values[MOODLE_PUBLIC_HOST]-}"; then
        compose_files+=(infra/moodle/compose.crm.prod.yaml)
    fi
    for file in "${compose_files[@]}"; do
        moodle_files+=(-f "$file")
    done
fi
compose "${moodle_files[@]}" up -d --wait --build backend clamav web
compose "${moodle_files[@]}" up -d --wait --force-recreate --no-deps web
compose "${moodle_files[@]}" --profile demo run --rm -T --build backend-bootstrap
if [[ ${values[MFA_REQUIRED_FOR_PRIVILEGED]} == true ]]; then
    printf 'Second factor (authenticator app) is required at sign-in for administrators and enrolment operators\n'
else
    printf 'Second factor is not required: MFA_REQUIRED_FOR_PRIVILEGED=false in %s\n' "$env_file"
fi
if [[ ${values[DEMO_DATA]} == false ]]; then
    printf 'CRM bootstrap completed without demo data. Administrator %s: initial password CRM_ADMIN_PASSWORD in %s, it must be changed at first sign-in\n' \
        "${values[CRM_ADMIN_USERNAME]}" "$env_file"
elif [[ ${values[DEMO_ACCOUNTS_SECURED]-} == true ]]; then
    printf 'Demo bootstrap completed. Demo accounts stay secured: their passwords and disabled state were not changed\n'
else
    printf 'Demo bootstrap completed. Local credentials are in %s\n' "$env_file"
fi
if [[ ${values[MOODLE_BASE_URL]-} == "$moodle_demo_url" ]]; then
    printf 'Demo Moodle: http://localhost:%s, administrator and read-only jury (crm-jury) credentials are in %s\n' \
        "${MOODLE_HTTP_PORT:-8082}" "$project_root/infra/moodle/.env.local"
fi
