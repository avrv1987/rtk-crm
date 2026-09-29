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
suffix=
if grep -Eq '^[0-9a-f]{64} [ *]crm[.]dump[.]enc$' "$backup_dir/SHA256SUMS"; then
    suffix=.enc
    source "$project_root/scripts/backup-crypto.sh"
    backup_key_setup "$env_file"
    for dump in crm.dump keycloak.dump; do
        [[ $(backup_dump_header "$backup_dir/$dump.enc") == PGDMP ]] \
            || fail "$dump.enc cannot be decrypted: the backup encryption key does not match this copy"
    done
fi
reports_archive=$backup_dir/reports.tar$suffix
reports_mode=restore
if ! grep -Eq "^[0-9a-f]{64} [ *]reports[.]tar${suffix/./[.]}\$" "$backup_dir/SHA256SUMS"; then
    [[ ! -e $reports_archive ]] || fail "reports.tar$suffix in $backup_dir is not covered by SHA256SUMS"
    printf 'restore: %s has no reports.tar (made before report files were backed up): report files are not restored, finished reports answer 410 REPORT_RESULT_UNAVAILABLE and have to be ordered again\n' "$backup_dir" >&2
    reports_archive=/dev/null
    reports_mode=clear
fi

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

read_archive() {
    if [[ -z $suffix || $1 == /dev/null ]]; then
        cat -- "$1"
    else
        backup_decrypt < "$1"
    fi
}

restore_database() {
    compose exec -T postgres sh -c '
        database=$(printenv "$1") && owner=$(printenv "$2") &&
        dropdb --username="$POSTGRES_USER" --force --if-exists "$database" &&
        createdb --username="$POSTGRES_USER" --owner="$owner" "$database"
    ' sh "$1" "$2" < /dev/null
    compose exec -T postgres sh -c '
        pg_restore --username="$POSTGRES_USER" --dbname="$(printenv "$1")" --role="$(printenv "$2")" --no-owner --no-privileges --exit-on-error
    ' sh "$1" "$2"
}

compose up -d --wait postgres
compose stop web backend keycloak
read_archive "$backup_dir/crm.dump$suffix" | restore_database CRM_DB_NAME CRM_DB_USER
read_archive "$backup_dir/keycloak.dump$suffix" | restore_database KEYCLOAK_DB_NAME KEYCLOAK_DB_USER
read_archive "$backup_dir/attachments.tar$suffix" | compose run --rm --no-deps -T --entrypoint sh backend -c '
    find "$APP_ATTACHMENTS_STORAGE_ROOT" -mindepth 1 -delete &&
    tar -C "$APP_ATTACHMENTS_STORAGE_ROOT" -xf -
'
read_archive "$reports_archive" | compose run --rm --no-deps -T --user root --entrypoint sh backend -c '
    find "$APP_REPORTS_STORAGE_ROOT" -mindepth 1 -delete &&
    if [ "$1" = restore ]; then tar -C "$APP_REPORTS_STORAGE_ROOT" --no-same-owner -xf -; fi &&
    chown -R crm:crm "$APP_REPORTS_STORAGE_ROOT"
' sh "$reports_mode"
compose up -d --wait --build keycloak clamav backend web
printf 'Restore completed from %s\n' "$backup_dir"
