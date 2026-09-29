#!/usr/bin/env bash
set -Eeuo pipefail

fail() {
    printf 'backup-scheduled: %s\n' "$1" >&2
    exit 1
}

[[ $# -ge 1 && $# -le 3 ]] || fail 'usage: backup-scheduled.sh <backup-root> [copies-to-keep] [env-file]'
keep=${2:-7}
[[ $keep =~ ^[1-9][0-9]*$ ]] || fail 'copies-to-keep must be a positive integer'
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
umask 077
mkdir -p -- "$1"
root=$(cd "$1" && pwd)
log=$root/backup.log
stamp=$(date -u +%Y-%m-%dT%H%M%SZ)
partial=

log_line() {
    local line
    line="$(date -u +%Y-%m-%dT%H:%M:%SZ) $1"
    if ! { printf '%s\n' "$line" >> "$log"; } 2>/dev/null; then
        printf 'backup-scheduled: %s (backup.log is not writable)\n' "$line" >&2
    fi
}

report_failure() {
    local message="backup $stamp failed: $1; last successful copy: $(cat "$root/last-success" 2>/dev/null || printf 'none')"
    log_line "FAILED $1"
    [[ -z $partial ]] || rm -rf -- "$partial"
    printf 'backup-scheduled: %s\n' "$message" >&2
    if command -v logger >/dev/null 2>&1; then
        logger -t rtk-crm-backup -p user.err -- "$message"
    fi
    exit 1
}

[[ -w $root ]] || report_failure "$root is not writable"
command -v flock >/dev/null 2>&1 || report_failure 'flock is not installed'
exec 9> "$root/.lock" || report_failure "cannot open $root/.lock"
flock -n 9 || report_failure 'another backup is already running'
partial=$root/.partial-$stamp
[[ ! -e $root/$stamp ]] || report_failure "$root/$stamp already exists"
find "$root" -mindepth 1 -maxdepth 1 -type d -name '.partial-*' -exec rm -rf -- {} +
log_line "START $stamp"
backup_args=("$partial")
[[ -z ${3-} ]] || backup_args+=("$3")
"$project_root/scripts/backup.sh" "${backup_args[@]}" >> "$log" 2>&1 || report_failure "backup.sh exited with code $?"
(cd "$partial" && sha256sum --check --quiet SHA256SUMS) >> "$log" 2>&1 || report_failure 'checksum verification failed'
mv -- "$partial" "$root/$stamp"

mapfile -t copies < <(find "$root" -mindepth 1 -maxdepth 1 -type d \
    -name '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z' | sort)
for ((i = 0; i < ${#copies[@]} - keep; i++)); do
    rm -rf -- "${copies[i]}"
    log_line "ROTATED ${copies[i]##*/}"
done
printf '%s\n' "$stamp" > "$root/last-success"
log_line "OK $stamp"
