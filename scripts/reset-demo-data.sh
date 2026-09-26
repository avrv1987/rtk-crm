#!/usr/bin/env bash
set -Eeuo pipefail

trap 'printf "reset-demo-data: failed at line %s\n" "$LINENO" >&2' ERR

fail() {
    printf 'reset-demo-data: %s\n' "$1" >&2
    exit 1
}

[[ $# -le 1 ]] || fail 'usage: reset-demo-data.sh [env-file]'
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${1:-$project_root/.env.local}
[[ -f $env_file ]] || fail "$env_file not found"
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")
identity_file=$project_root/.demo-identities.yml
[[ -f $identity_file ]] || fail "$identity_file not found; run scripts/bootstrap-demo.sh first"

env_value() {
    sed -n "s/^$1=//p" "$env_file" | tr -d '\r' | tail -n 1
}

[[ $(env_value DEMO_DATA) != false ]] && ! grep -q '^    demo-data: false' "$identity_file" \
    || fail 'reset is available only on a demo stand (DEMO_DATA=true)'

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

moodle_files=()
if [[ $(env_value MOODLE_BASE_URL) == http://moodle:8080 ]]; then
    separator=:
    [[ $OSTYPE == msys* || $OSTYPE == cygwin* ]] && separator=';'
    compose_file=$(env_value COMPOSE_FILE)
    IFS=${COMPOSE_PATH_SEPARATOR:-$separator} read -r -a compose_files <<< "${COMPOSE_FILE:-${compose_file:-compose.yaml}}"
    compose_files+=(infra/moodle/compose.crm.yaml)
    [[ -z $(env_value MOODLE_PUBLIC_HOST) ]] || compose_files+=(infra/moodle/compose.crm.prod.yaml)
    for file in "${compose_files[@]}"; do
        moodle_files+=(-f "$file")
    done
fi

compose "${moodle_files[@]}" --profile demo-bootstrap run --rm -e APP_DEMOBOOTSTRAP_RESET=true backend-bootstrap
compose exec -T backend sh -c 'find "$APP_ATTACHMENTS_STORAGE_ROOT" "${APP_REPORTS_STORAGE_ROOT:-/var/lib/rtk-crm/reports}" -mindepth 1 -delete'
printf 'Demo data reset completed: CRM data, attachments and report files match a fresh bootstrap; Keycloak accounts and passwords were not changed\n'
