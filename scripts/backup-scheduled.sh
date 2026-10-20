#!/usr/bin/env bash
set -Eeuo pipefail

fail() {
    printf 'backup-scheduled: %s\n' "$1" >&2
    exit 1
}

[[ $# -ge 1 && $# -le 3 ]] || fail 'usage: backup-scheduled.sh <backup-root> [copies-to-keep] [env-file]'
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${3:-$project_root/.env.local}
[[ -f $env_file ]] || fail "$env_file not found"
source "$project_root/scripts/backup-lib.sh"
# Число копий и место вне сервера — настройки env-файла; аргумент командной строки сильнее BACKUP_KEEP.
keep=${2:-${BACKUP_KEEP:-$(env_value BACKUP_KEEP "$env_file")}}
keep=${keep:-7}
[[ $keep =~ ^[1-9][0-9]*$ ]] || fail 'copies-to-keep (BACKUP_KEEP) must be a positive integer'
offsite_dir=${BACKUP_OFFSITE_DIR:-$(env_value BACKUP_OFFSITE_DIR "$env_file")}
offsite_command=${BACKUP_OFFSITE_COMMAND:-$(env_value BACKUP_OFFSITE_COMMAND "$env_file")}
umask 077
mkdir -p -- "$1"
root=$(cd "$1" && pwd)
log=$root/backup.log
stamp=$(date -u +%Y-%m-%dT%H%M%SZ)
partial=
mark_failure=1

compose() {
    (cd "$project_root" && docker compose --env-file "$env_file" "$@")
}

report_failure() {
    local message="backup $stamp failed: $1; last successful copy: $(cat "$root/last-success" 2>/dev/null || printf 'none')"
    log_line "FAILED $1"
    [[ -z $partial ]] || rm -rf -- "$partial"
    printf 'backup-scheduled: %s\n' "$message" >&2
    if command -v logger >/dev/null 2>&1; then
        logger -t rtk-crm-backup -p user.err -- "$message"
    fi
    # Сбой второго запуска при занятой блокировке — не сбой копирования: отметку первого запуска не трогаем.
    if [[ -n $mark_failure ]]; then
        backup_mark .last-backup failed "$1" >> "$log" 2>&1 || log_line 'the failure mark for the admin overview was not written'
    fi
    exit 1
}

rotate() {
    local -a copies
    local i
    mapfile -t copies < <(find "$1" -mindepth 1 -maxdepth 1 -type d \
        -name '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z' | sort)
    for ((i = 0; i < ${#copies[@]} - keep; i++)); do
        rm -rf -- "${copies[i]}"
        log_line "ROTATED ${copies[i]}"
    done
}

[[ -w $root ]] || report_failure "$root is not writable"
command -v flock >/dev/null 2>&1 || report_failure 'flock is not installed'
exec 9> "$root/.lock" || report_failure "cannot open $root/.lock"
flock -n 9 || { mark_failure=; report_failure "another backup is already running"; }
partial=$root/.partial-$stamp
[[ ! -e $root/$stamp ]] || report_failure "$root/$stamp already exists"
find "$root" -mindepth 1 -maxdepth 1 -type d -name '.partial-*' -exec rm -rf -- {} +
log_line "START $stamp"
"$project_root/scripts/backup.sh" "$partial" "$env_file" >> "$log" 2>&1 || report_failure "backup.sh exited with code $?"
(cd "$partial" && sha256sum --check --quiet SHA256SUMS) >> "$log" 2>&1 || report_failure 'checksum verification failed'
mv -- "$partial" "$root/$stamp"
partial=
rotate "$root"
printf '%s\n' "$stamp" > "$root/last-success"
log_line "OK $stamp"

# Хранилище вне сервера выбирается позже, поэтому здесь только две общие формы: каталог (смонтированный диск, NFS,
# rclone mount) или команда, которая получает путь копии первым аргументом. Сбой передачи — сбой запуска: локальная
# копия при этом цела, а «Обзор» и журнал показывают причину.
if [[ -n $offsite_dir ]]; then
    [[ -d $offsite_dir && -w $offsite_dir ]] || report_failure "BACKUP_OFFSITE_DIR $offsite_dir is not a writable directory"
    rm -rf -- "$offsite_dir/.partial-$stamp"
    cp -R -- "$root/$stamp" "$offsite_dir/.partial-$stamp" && mv -- "$offsite_dir/.partial-$stamp" "$offsite_dir/$stamp" \
        || report_failure "copy to BACKUP_OFFSITE_DIR $offsite_dir failed"
    rotate "$offsite_dir"
    log_line "OFFSITE $offsite_dir/$stamp"
fi
if [[ -n $offsite_command ]]; then
    sh -c "$offsite_command" offsite "$root/$stamp" >> "$log" 2>&1 || report_failure "BACKUP_OFFSITE_COMMAND exited with code $?"
    log_line "OFFSITE command $stamp"
fi
