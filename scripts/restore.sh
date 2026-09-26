#!/usr/bin/env bash
set -Eeuo pipefail

trap 'printf "restore: failed at line %s\n" "$LINENO" >&2' ERR

fail() {
    printf 'restore: %s\n' "$1" >&2
    exit 1
}

[[ $# -ge 1 && $# -le 2 ]] || fail 'usage: restore.sh <backup-directory> [env-file]'
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
backup_dir=$(cd "$1" && pwd)
env_file=${2:-$project_root/.env.local}
[[ -f $env_file ]] || fail "$env_file not found"
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")

(cd "$backup_dir" && sha256sum --check --quiet SHA256SUMS) || fail "checksum mismatch in $backup_dir"

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

restore_database() {
    compose exec -T postgres sh -c '
        database=$(printenv "$1") && owner=$(printenv "$2") &&
        dropdb --username="$POSTGRES_USER" --force --if-exists "$database" &&
        createdb --username="$POSTGRES_USER" --owner="$owner" "$database"
    ' sh "$1" "$2"
    compose exec -T postgres sh -c '
        pg_restore --username="$POSTGRES_USER" --dbname="$(printenv "$1")" --role="$(printenv "$2")" --no-owner --no-privileges --exit-on-error
    ' sh "$1" "$2" < "$3"
}

compose up -d --wait postgres
compose stop web backend keycloak
restore_database CRM_DB_NAME CRM_DB_USER "$backup_dir/crm.dump"
restore_database KEYCLOAK_DB_NAME KEYCLOAK_DB_USER "$backup_dir/keycloak.dump"
compose run --rm --no-deps -T --entrypoint sh backend -c '
    find "$APP_ATTACHMENTS_STORAGE_ROOT" -mindepth 1 -delete &&
    tar -C "$APP_ATTACHMENTS_STORAGE_ROOT" -xf -
' < "$backup_dir/attachments.tar"
compose up -d --wait --build keycloak clamav backend web
printf 'Restore completed from %s\n' "$backup_dir"
