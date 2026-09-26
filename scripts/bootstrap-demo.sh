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
site_fixture_url=http://site-fixture:8080

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
)
if [[ ${values[DEMO_DATA]} == true ]]; then
    defaults+=(SITE_BASE_URL=$site_fixture_url DEMO_LMS=true ENROLMENT_ENABLED=true)
    secrets+=(DEMO_USER_PASSWORD SITE_TOKEN)
else
    [[ ${values[DEMO_LMS]-} != true ]] || fail 'DEMO_LMS=true requires DEMO_DATA=true'
    [[ ${values[SITE_BASE_URL]-} != "$site_fixture_url" ]] || set_value SITE_BASE_URL ''
    defaults+=(DEMO_LMS=false ENROLMENT_ENABLED=false CRM_ADMIN_USERNAME=admin 'CRM_ADMIN_DISPLAY_NAME=Администратор')
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
write_env_file

moodle_demo_url=http://moodle:8080
moodle_demo_keys=(MOODLE_BASE_URL MOODLE_TOKEN MOODLE_COURSE_IDS MOODLE_DEMO_JAVA_COURSE MOODLE_DEMO_DATA_GROUP)
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

compose up -d --wait postgres keycloak
compose exec -T keycloak bash -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null
kcadm update realms/rtk-crm -f /opt/keycloak/data/import/rtk-crm-realm.json

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
client_definition=$(printf '{"clientId":"crm-bff","enabled":true,"protocol":"openid-connect","publicClient":false,"standardFlowEnabled":true,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":false,"secret":%s,"redirectUris":[%s],"webOrigins":[%s],"attributes":{"post.logout.redirect.uris":%s}}' \
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

sync_client_definition=$(printf '{"clientId":"crm-account-sync","enabled":true,"protocol":"openid-connect","publicClient":false,"standardFlowEnabled":false,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":true,"secret":%s}' \
    "$(json_string "${values[CRM_ACCOUNT_SYNC_CLIENT_SECRET]}")")
sync_client_id=$(kcadm get clients -r rtk-crm -q clientId=crm-account-sync --fields id --format csv --noquotes)
if [[ -z $sync_client_id ]]; then
    sync_client_id=$(printf '%s' "$sync_client_definition" | kcadm create clients -r rtk-crm -f - -i)
fi
sync_client_id=$(single_id 'CRM account sync client' "$sync_client_id")
printf '%s' "$sync_client_definition" | kcadm update "clients/$sync_client_id" -r rtk-crm -f -
kcadm add-roles -r rtk-crm --uusername service-account-crm-account-sync --cclientid realm-management --rolename manage-users

demo_identities=(
    'kam-a|КАМ А|USER|team-a'
    'kam-b|КАМ Б|USER|team-b'
    'kam-c|КАМ В|USER|team-a'
    'kam-d|КАМ Г|USER|team-a'
    'leader|Руководитель|LEADER|team-a'
    'leader-b|Руководитель Б|LEADER|team-b'
    'admin|Администратор|ADMIN|team-a'
    'enrol|Оператор зачисления|USER|open-enrolment|operator'
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
}

if [[ ${values[DEMO_DATA]} == true ]]; then
    for entry in "${demo_identities[@]}" "${spare_accounts[@]}"; do
        IFS='|' read -r username display_name _ <<< "$entry"
        ensure_user "$username" "$display_name"
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
    identities:
YAML
        for entry in "${demo_identities[@]}"; do
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
        fi
    } > "$identity_file"
else
    ensure_administrator
    {
        printf 'app:\n  demo-bootstrap:\n    demo-data: false\n    identities:\n'
        identity_yaml "${values[CRM_ADMIN_USERNAME]}" "${values[CRM_ADMIN_DISPLAY_NAME]}" ADMIN
    } > "$identity_file"
fi

if [[ ${values[SITE_BASE_URL]-} == "$site_fixture_url" ]]; then
    compose --profile demo-sources up -d --wait site-fixture
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
compose "${moodle_files[@]}" --profile demo-bootstrap run --rm --build backend-bootstrap
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
