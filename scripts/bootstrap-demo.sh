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
if [[ -n ${DEMO_LMS-} ]]; then
    set_value DEMO_LMS "$DEMO_LMS"
fi

defaults=(
    POSTGRES_SUPERUSER=postgres
    CRM_DB_NAME=rtk_crm
    CRM_DB_USER=crm
    KEYCLOAK_DB_NAME=keycloak
    KEYCLOAK_DB_USER=keycloak
    KEYCLOAK_ADMIN_USERNAME=bootstrap-admin
    PUBLIC_ORIGIN=http://rtk.localhost:8081
    SITE_BASE_URL=http://site-fixture:8080
    DEMO_LMS=true
    'SOURCES_SYNC_CRON=0 0 * * * *'
)
secrets=(
    POSTGRES_SUPERUSER_PASSWORD
    CRM_DB_PASSWORD
    KEYCLOAK_DB_PASSWORD
    KEYCLOAK_ADMIN_PASSWORD
    CRM_OIDC_CLIENT_SECRET
    DEMO_USER_PASSWORD
    SITE_TOKEN
)
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

ensure_user() {
    local username=$1 first_name=$2 definition id
    definition=$(printf '{"username":%s,"firstName":%s,"lastName":"Demo","email":%s,"enabled":true,"emailVerified":true,"requiredActions":[]}' \
        "$(json_string "$username")" "$(json_string "$first_name")" "$(json_string "$username@demo.rtk.local")")
    id=$(kcadm get users -r rtk-crm -q "username=$username" -q exact=true --fields id --format csv --noquotes)
    if [[ -z $id ]]; then
        id=$(printf '%s' "$definition" | kcadm create users -r rtk-crm -f - -i)
    fi
    id=$(single_id "Keycloak user $username" "$id")
    printf '{"type":"password","value":%s,"temporary":false}' "$(json_string "${values[DEMO_USER_PASSWORD]}")" \
        | kcadm update "users/$id/reset-password" -r rtk-crm -f -
    printf '%s' "$definition" | kcadm update "users/$id" -r rtk-crm -f -
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

ensure_user kam-a 'КАМ А'
ensure_user kam-b 'КАМ Б'
ensure_user kam-c 'КАМ В'
ensure_user leader 'Руководитель'
ensure_user admin 'Администратор'
ensure_user unprofiled 'Без профиля CRM'

issuer=$(yaml_literal "$public_origin/idp/realms/rtk-crm")
cat > "$project_root/.demo-identities.yml" <<YAML
app:
  demo-bootstrap:
    identities:
      - key: 'kam-a'
        issuer: $issuer
        subject: $(yaml_literal "${subjects[kam-a]}")
        display-name: 'КАМ А'
        role: USER
        team-key: 'team-a'
      - key: 'kam-b'
        issuer: $issuer
        subject: $(yaml_literal "${subjects[kam-b]}")
        display-name: 'КАМ Б'
        role: USER
        team-key: 'team-b'
      - key: 'kam-c'
        issuer: $issuer
        subject: $(yaml_literal "${subjects[kam-c]}")
        display-name: 'КАМ В'
        role: USER
        team-key: 'team-a'
      - key: 'leader'
        issuer: $issuer
        subject: $(yaml_literal "${subjects[leader]}")
        display-name: 'Руководитель'
        role: LEADER
        team-key: 'team-a'
      - key: 'admin'
        issuer: $issuer
        subject: $(yaml_literal "${subjects[admin]}")
        display-name: 'Администратор'
        role: ADMIN
        team-key: 'team-a'
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
YAML

if [[ ${values[MOODLE_BASE_URL]-} == "$moodle_demo_url" ]] \
    && ! is_blank "${values[MOODLE_DEMO_JAVA_COURSE]-}" && ! is_blank "${values[MOODLE_DEMO_DATA_GROUP]-}"; then
    cat >> "$project_root/.demo-identities.yml" <<YAML
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

if [[ ${values[SITE_BASE_URL]} == http://site-fixture:8080 ]]; then
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
printf 'Demo bootstrap completed. Local credentials are in %s\n' "$env_file"
if [[ ${values[MOODLE_BASE_URL]-} == "$moodle_demo_url" ]]; then
    printf 'Demo Moodle: http://localhost:%s, administrator and read-only jury (crm-jury) credentials are in %s\n' \
        "${MOODLE_HTTP_PORT:-8082}" "$project_root/infra/moodle/.env.local"
fi
