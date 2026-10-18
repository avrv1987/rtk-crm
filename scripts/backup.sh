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
source "$project_root/scripts/backup-crypto.sh"
backup_key_setup "$env_file" generate

umask 077
mkdir -p -- "$1"
backup_dir=$(cd "$1" && pwd)
[[ -z $(ls -A "$backup_dir") ]] || fail "$backup_dir is not empty"

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

compose run --rm --no-deps -T --entrypoint sh backend -c 'tar -C "$APP_REPORTS_STORAGE_ROOT" -cf - .' \
    | backup_encrypt > "$backup_dir/reports.tar.enc"
compose exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --format=custom "$CRM_DB_NAME"' \
    | backup_encrypt > "$backup_dir/crm.dump.enc"
compose exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --format=custom "$KEYCLOAK_DB_NAME"' \
    | backup_encrypt > "$backup_dir/keycloak.dump.enc"
compose run --rm --no-deps -T --entrypoint sh backend -c 'tar -C "$APP_ATTACHMENTS_STORAGE_ROOT" --exclude=./.last-backup -cf - .' \
    | backup_encrypt > "$backup_dir/attachments.tar.enc"
for dump in crm.dump keycloak.dump; do
    [[ $(backup_dump_header "$backup_dir/$dump.enc") == PGDMP ]] || fail "$dump is not a PostgreSQL custom-format dump"
done
(cd "$backup_dir" && sha256sum crm.dump.enc keycloak.dump.enc attachments.tar.enc reports.tar.enc > SHA256SUMS)
# Отметка для «Обзора» администратора: время последней успешной копии. В архив вложений она не входит, чтобы после
# восстановления не показывать время чужой копии. Сбой записи отметки копию не портит.
compose run --rm --no-deps -T --entrypoint sh backend -c 'date -u +%Y-%m-%dT%H:%M:%SZ > "$APP_ATTACHMENTS_STORAGE_ROOT/.last-backup"' \
    || printf 'backup: the last-backup mark for the admin overview was not written\n' >&2
printf 'Backup completed: %s\n' "$backup_dir"
