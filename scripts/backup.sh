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
source "$project_root/scripts/backup-lib.sh"
backup_key_setup "$env_file" generate

umask 077
mkdir -p -- "$1"
backup_dir=$(cd "$1" && pwd)
[[ -z $(ls -A "$backup_dir") ]] || fail "$backup_dir is not empty"

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

# Согласованность копии: пока снимаются БД и файлы, backend остановлен. Только он пишет в БД CRM, вложения и отчёты,
# поэтому в копии не бывает записи без файла или файла без записи. Keycloak не останавливается: его БД снимается одним
# снимком pg_dump, а связь учётных записей с профилями CRM меняет только backend.
backend_stopped=
resume_backend() {
    if [[ -n $backend_stopped ]]; then
        backend_stopped=
        compose start backend > /dev/null \
            || printf 'backup: backend was not started again, start it by hand: docker compose start backend\n' >&2
    fi
}
trap resume_backend EXIT

wait_backend_healthy() {
    local id status attempt
    id=$(compose ps -q backend)
    for ((attempt = 0; attempt < 60; attempt++)); do
        status=$(docker inspect --format '{{.State.Health.Status}}' "$id" 2>/dev/null || true)
        [[ $status != healthy ]] || return 0
        sleep 5
    done
    return 1
}

archive_files() {
    backup_decrypt < "$1" | tar -tf - | { grep -v '/$' || true; } | wc -l | tr -d ' '
}

pause_started=$SECONDS
running=$(compose ps -q --status running backend)
if [[ -n $running ]]; then
    backend_stopped=1
    compose stop backend > /dev/null
fi
created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
compose run --rm --no-deps -T --entrypoint sh backend -c 'tar -C "$APP_REPORTS_STORAGE_ROOT" -cf - .' < /dev/null \
    | backup_encrypt > "$backup_dir/reports.tar.enc"
compose exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --format=custom "$CRM_DB_NAME"' < /dev/null \
    | backup_encrypt > "$backup_dir/crm.dump.enc"
compose exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --format=custom "$KEYCLOAK_DB_NAME"' < /dev/null \
    | backup_encrypt > "$backup_dir/keycloak.dump.enc"
compose run --rm --no-deps -T --entrypoint sh backend \
    -c 'tar -C "$APP_ATTACHMENTS_STORAGE_ROOT" --exclude=./.last-backup --exclude=./.last-restore-check -cf - .' < /dev/null \
    | backup_encrypt > "$backup_dir/attachments.tar.enc"
counts=$(backup_counts)
[[ $counts == *flyway_version=* ]] || fail 'record counts for the manifest were not read'
attachments_files=$(backup_file_count APP_ATTACHMENTS_STORAGE_ROOT)
reports_files=$(backup_file_count APP_REPORTS_STORAGE_ROOT)
if [[ -n $backend_stopped ]]; then
    compose start backend > /dev/null
    backend_stopped=
    wait_backend_healthy || fail 'backend did not become healthy after the copy: check docker compose logs backend'
fi
pause_seconds=$((SECONDS - pause_started))

# Проверка целостности: оба дампа и оба архива расшифровываются целиком, в архивах столько же файлов, сколько было в томах.
for dump in crm.dump keycloak.dump; do
    [[ $(backup_dump_header "$backup_dir/$dump.enc") == PGDMP ]] || fail "$dump is not a PostgreSQL custom-format dump"
    backup_decrypt < "$backup_dir/$dump.enc" > /dev/null || fail "$dump.enc cannot be decrypted completely"
done
[[ $(archive_files "$backup_dir/attachments.tar.enc") == "$attachments_files" ]] \
    || fail "attachments.tar.enc does not hold the $attachments_files files of the attachments volume"
[[ $(archive_files "$backup_dir/reports.tar.enc") == "$reports_files" ]] \
    || fail "reports.tar.enc does not hold the $reports_files files of the reports volume"

archives=(crm.dump.enc keycloak.dump.enc attachments.tar.enc reports.tar.enc)
{
    printf 'format=1\ncreated_at=%s\n' "$created_at"
    printf 'app_version=%s\n' "$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' frontend/package.json | head -n 1)"
    printf 'commit=%s\n' "$(git -C "$project_root" rev-parse HEAD 2>/dev/null || printf unknown)"
    printf 'backend_pause_seconds=%s\n' "$pause_seconds"
    printf '%s\n' "$counts"
    printf 'files.attachments=%s\nfiles.reports=%s\n' "$attachments_files" "$reports_files"
    (cd "$backup_dir" && sha256sum "${archives[@]}") | while read -r sum name; do
        printf 'sha256.%s=%s\n' "${name#\*}" "$sum"
    done
} > "$backup_dir/MANIFEST"
(cd "$backup_dir" && sha256sum "${archives[@]}" MANIFEST > SHA256SUMS)
backup_mark .last-backup ok '' "bytes=$(du -sb "$backup_dir" | cut -f1)" \
    || printf 'backup: the mark for the admin overview was not written\n' >&2
printf 'Backup completed: %s (backend paused for %s s)\n' "$backup_dir" "$pause_seconds"
