#!/usr/bin/env bash
set -Eeuo pipefail

trap 'printf "backup: failed at line %s\n" "$LINENO" >&2' ERR

fail() {
    printf 'backup: %s\n' "$1" >&2
    exit 1
}

[[ $# -ge 1 && $# -le 2 ]] || fail 'usage: backup.sh <backup-directory> [env-file]'
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${2:-$project_root/.env.local}
[[ -f $env_file ]] || fail "$env_file not found"
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")

umask 077
mkdir -p -- "$1"
backup_dir=$(cd "$1" && pwd)
[[ -z $(ls -A "$backup_dir") ]] || fail "$backup_dir is not empty"

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

compose run --rm --no-deps -T --entrypoint sh backend -c 'tar -C "$APP_REPORTS_STORAGE_ROOT" -cf - .' > "$backup_dir/reports.tar"
compose exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --format=custom "$CRM_DB_NAME"' > "$backup_dir/crm.dump"
compose exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --format=custom "$KEYCLOAK_DB_NAME"' > "$backup_dir/keycloak.dump"
compose run --rm --no-deps -T --entrypoint sh backend -c 'tar -C "$APP_ATTACHMENTS_STORAGE_ROOT" -cf - .' > "$backup_dir/attachments.tar"
(cd "$backup_dir" && sha256sum crm.dump keycloak.dump attachments.tar reports.tar > SHA256SUMS)
printf 'Backup completed: %s\n' "$backup_dir"
