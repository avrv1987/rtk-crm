#!/usr/bin/env bash
set -Eeuo pipefail
export MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:-/opt/}"

trap 'printf "seed-load-data: failed at line %s\n" "$LINENO" >&2' ERR

fail() {
    printf 'seed-load-data: %s\n' "$1" >&2
    exit 1
}

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
env_file=${1:-$project_root/.env.local}
[[ -f $env_file ]] || fail "$env_file not found"
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")
state_dir=${LOAD_STATE_DIR:-$project_root/.load-test}
users_file=$state_dir/users.env
user_count=50

public_origin=$(sed -n 's/^PUBLIC_ORIGIN=//p' "$env_file" | tr -d '\r')
public_origin=${public_origin:-http://rtk.localhost:8081}
public_origin=${public_origin%/}

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

crm_sql() {
    compose exec -T postgres sh -c 'psql -X -q -v ON_ERROR_STOP=1 -At --username="$POSTGRES_USER" --dbname="$CRM_DB_NAME"'
}

new_secret() {
    openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\r\n'
}

existing=$(printf "SELECT count(*) FROM teams WHERE name LIKE 'LOAD-%%';\n" | crm_sql | tr -d '\r')
[[ $existing == 0 ]] || fail 'load data already exists; run scripts/load/remove-load-data.sh first'

umask 077
mkdir -p "$state_dir"
crm_sql < "$project_root/scripts/load/demo-fingerprint.sql" | tr -d '\r' > "$state_dir/demo-before.txt"

declare -A passwords=()
{
    for n in $(seq 1 "$user_count"); do
        username=$(printf 'load-%03d' "$n")
        passwords[$username]=$(new_secret)
        printf '%s=%s\n' "$username" "${passwords[$username]}"
    done
} > "$users_file"
chmod 600 "$users_file"

keycloak_script() {
    printf 'set -euo pipefail\n'
    printf 'kc=/opt/keycloak/bin/kcadm.sh\n'
    printf '"$kc" config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null\n'
    for n in $(seq 1 "$user_count"); do
        username=$(printf 'load-%03d' "$n")
        if (( n <= 5 )); then
            first_name=$(printf 'LOAD-руководитель %02d' "$n")
        else
            first_name=$(printf 'LOAD-КАМ %02d' "$n")
        fi
        printf 'id=$("$kc" get users -r rtk-crm -q username=%s -q exact=true --fields id --format csv --noquotes)\n' "$username"
        printf 'if [[ -z $id ]]; then id=$("$kc" create users -r rtk-crm -f - -i <<'"'"'JSON'"'"'\n'
        printf '{"username":"%s","firstName":"%s","lastName":"Load","email":"%s@load.rtk.local","enabled":true,"emailVerified":true,"requiredActions":[]}\n' \
            "$username" "$first_name" "$username"
        printf 'JSON\n); fi\n'
        printf '"$kc" update "users/$id/reset-password" -r rtk-crm -f - <<'"'"'JSON'"'"'\n'
        printf '{"type":"password","value":"%s","temporary":false}\n' "${passwords[$username]}"
        printf 'JSON\n'
        printf 'printf "%%s %%s\\n" %s "$id"\n' "$username"
    done
}

subjects=$(keycloak_script | compose exec -T keycloak bash -s | tr -d '\r')
[[ $(grep -c '^load-[0-9]\{3\} [0-9a-f-]\{36\}$' <<< "$subjects") == "$user_count" ]] || fail 'Keycloak did not return all load users'

{
    printf 'BEGIN;\n'
    printf 'CREATE TEMP TABLE load_users (n INTEGER PRIMARY KEY, subject VARCHAR(512) NOT NULL);\n'
    printf 'INSERT INTO load_users (n, subject) VALUES\n'
    sed -E 's/^load-0*([0-9]+) (.*)$/(\1, '"'"'\2'"'"')/' <<< "$subjects" | paste -sd, -
    printf ';\n'
    printf "\\\\set issuer '%s/idp/realms/rtk-crm'\n" "$public_origin"
    cat "$project_root/scripts/load/seed-load-data.sql"
    printf 'COMMIT;\n'
} | crm_sql

printf 'Load data created: %s users, credentials in %s\n' "$user_count" "$users_file"
