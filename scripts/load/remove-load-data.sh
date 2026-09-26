#!/usr/bin/env bash
set -Eeuo pipefail
export MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:-/opt/;/var/}"

trap 'printf "remove-load-data: failed at line %s\n" "$LINENO" >&2' ERR

fail() {
    printf 'remove-load-data: %s\n' "$1" >&2
    exit 1
}

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
env_file=${1:-$project_root/.env.local}
[[ -f $env_file ]] || fail "$env_file not found"
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")
state_dir=${LOAD_STATE_DIR:-$project_root/.load-test}

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

crm_sql() {
    compose exec -T postgres sh -c 'psql -X -q -v ON_ERROR_STOP=1 -At --username="$POSTGRES_USER" --dbname="$CRM_DB_NAME"'
}

uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
output=$(crm_sql < "$project_root/scripts/load/remove-load-data.sql" | tr -d '\r')
report_files=()
attachment_files=()
while read -r kind key; do
    [[ $key =~ $uuid_pattern ]] || continue
    case $kind in
        report-file) report_files+=("$key") ;;
        attachment-file) attachment_files+=("$key") ;;
    esac
done <<< "$output"
if (( ${#report_files[@]} > 0 )); then
    compose exec -T backend sh -c 'cd /var/lib/rtk-crm/reports && rm -f -- "$@"' sh "${report_files[@]}"
fi
if (( ${#attachment_files[@]} > 0 )); then
    compose exec -T backend sh -c 'cd /var/lib/rtk-crm/files && rm -f -- "$@"' sh "${attachment_files[@]}"
fi

removed_users=$(compose exec -T keycloak bash -s <<'SCRIPT' | tr -d '\r'
set -euo pipefail
kc=/opt/keycloak/bin/kcadm.sh
"$kc" config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
count=0
while IFS=, read -r id username; do
    if [[ $username =~ ^load-[0-9]{3}$ ]]; then
        "$kc" delete "users/$id" -r rtk-crm
        count=$((count + 1))
    fi
done < <("$kc" get users -r rtk-crm -q username=load- -q max=1000 --fields id,username --format csv --noquotes)
printf '%s\n' "$count"
SCRIPT
)
rm -f "$state_dir/users.env"

remaining=$(printf "SELECT (SELECT count(*) FROM teams WHERE name LIKE 'LOAD-%%') + (SELECT count(*) FROM crm_user_profiles WHERE display_name LIKE 'LOAD-%%') + (SELECT count(*) FROM organizations WHERE name LIKE 'LOAD-%%') + (SELECT count(*) FROM source_records WHERE external_id LIKE 'LOAD-%%') + (SELECT count(*) FROM spring_session WHERE principal_name LIKE 'load-%%');\n" | crm_sql | tr -d '\r')
[[ $remaining == 0 ]] || fail "$remaining load rows remain"
printf 'Load data removed: report files %s, attachment files %s, Keycloak users %s\n' \
    "${#report_files[@]}" "${#attachment_files[@]}" "$removed_users"

if [[ -f $state_dir/demo-before.txt ]]; then
    crm_sql < "$project_root/scripts/load/demo-fingerprint.sql" | tr -d '\r' > "$state_dir/demo-after.txt"
    if diff "$state_dir/demo-before.txt" "$state_dir/demo-after.txt" > "$state_dir/demo-diff.txt"; then
        printf 'Demo data is unchanged: %s tables match the fingerprint taken before seeding\n' "$(wc -l < "$state_dir/demo-after.txt" | tr -d ' ')"
    else
        cat "$state_dir/demo-diff.txt"
        fail 'demo data differs from the fingerprint taken before seeding'
    fi
fi
