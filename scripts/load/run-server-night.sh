#!/usr/bin/env bash
set -Eeuo pipefail

trap 'printf "run-server-night: failed at line %s\n" "$LINENO" >&2' ERR

fail() {
    printf 'run-server-night: %s\n' "$1" >&2
    exit 1
}

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$project_root"

dry_run=false
env_file="$project_root/.env.local"
for arg in "$@"; do
    case $arg in
        --dry-run) dry_run=true ;;
        --help|-h) printf 'usage: run-server-night.sh [--dry-run] [env-file]\n'; exit 0 ;;
        *) env_file=$arg ;;
    esac
done
[[ -f $env_file ]] || fail "$env_file not found"
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")

load_users=${LOAD_NIGHT_USERS:-30}
load_duration_s=${LOAD_NIGHT_DURATION_S:-180}
load_report_bursts=${LOAD_NIGHT_REPORT_BURSTS:-60}
max_total_s=${LOAD_NIGHT_MAX_TOTAL_S:-1500}
atlas_url=${LOAD_NIGHT_ATLAS_URL:-https://atlas.baichein.ru/}
atlas_max_s=${LOAD_NIGHT_ATLAS_MAX_S:-1.5}
min_free_mb=${LOAD_NIGHT_MIN_FREE_MB:-400}
check_interval_s=${LOAD_NIGHT_CHECK_INTERVAL_S:-5}
node_image=${LOAD_NIGHT_NODE_IMAGE:-node:24-alpine}

public_origin=$(sed -n 's/^PUBLIC_ORIGIN=//p' "$env_file" | tail -1 | tr -d '\r')
[[ -n $public_origin ]] || fail "PUBLIC_ORIGIN is not set in $env_file"

state_dir=${LOAD_STATE_DIR:-$project_root/.load-test}
run_stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_dir="$project_root/.backups/night-$run_stamp"
summary_file="$state_dir/night-summary-$run_stamp.txt"
log_file="$state_dir/night-log-$run_stamp.txt"
load_container="rtk-crm-load-night-$run_stamp"
generator_summary=".load-test/night-generator-$run_stamp.txt"

seeded=false
started=false
abort_reason=""
run_result=""
exit_reason="completed"

run() {
    if $dry_run; then
        printf '[dry-run] would run: %s\n' "$*"
    else
        "$@"
    fi
}

free_mb() {
    awk '/MemAvailable/ { printf "%d", $2 / 1024 }' /proc/meminfo 2>/dev/null
}

atlas_ok() {
    local metrics code seconds
    metrics=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 5 "$atlas_url" 2>/dev/null) || return 1
    code=${metrics%% *}
    seconds=${metrics##* }
    [[ $code == 2* || $code == 3* ]] || return 1
    awk -v s="$seconds" -v max="$atlas_max_s" 'BEGIN { exit !(s <= max) }'
}

monitor() {
    local elapsed=0 free
    while [[ "$(docker inspect -f '{{.State.Running}}' "$load_container" 2>/dev/null)" == "true" ]]; do
        if ! atlas_ok; then
            abort_reason="atlas ($atlas_url) отвечает медленнее ${atlas_max_s}s или с ошибкой"
            docker stop -t 5 "$load_container" >/dev/null 2>&1 || true
            return
        fi
        free=$(free_mb)
        if [[ -n $free ]] && (( free < min_free_mb )); then
            abort_reason="свободной памяти на хосте меньше ${min_free_mb} МБ (сейчас ${free} МБ)"
            docker stop -t 5 "$load_container" >/dev/null 2>&1 || true
            return
        fi
        if (( elapsed >= max_total_s )); then
            abort_reason="превышен общий предел длительности ${max_total_s}s"
            docker stop -t 5 "$load_container" >/dev/null 2>&1 || true
            return
        fi
        sleep "$check_interval_s"
        elapsed=$(( elapsed + check_interval_s ))
    done
}

write_summary() {
    local exit_code=$1
    local body
    body=$(
        printf 'Ночной замер NFR-01/NFR-03 (D-6a562c)\n'
        printf 'запуск (UTC): %s\n' "$run_stamp"
        printf 'dry-run: %s\n' "$dry_run"
        printf 'сервер: PUBLIC_ORIGIN=%s\n' "$public_origin"
        printf 'нагрузка: пользователей=%s, длительность=%ss, серия отчётов на %ss секунде\n' "$load_users" "$load_duration_s" "$load_report_bursts"
        printf 'резервная копия: %s\n' "$backup_dir"
        printf 'тестовые данные загружены: %s\n' "$seeded"
        printf 'генератор нагрузки запускался: %s\n' "$started"
        if [[ -n $abort_reason ]]; then
            printf 'остановлено досрочно: %s\n' "$abort_reason"
        fi
        if [[ -n $run_result ]]; then
            printf 'итог генератора:\n%s\n' "$run_result"
        fi
        printf 'журнал контейнера: %s\n' "$log_file"
        printf 'причина завершения: %s\n' "$exit_reason"
        printf 'код завершения: %s\n' "$exit_code"
    )
    if $dry_run; then
        printf '[dry-run] would write summary to %s:\n%s\n' "$summary_file" "$body" >&2
    else
        mkdir -p "$state_dir"
        printf '%s\n' "$body" > "$summary_file"
        cat "$summary_file" >&2
    fi
}

cleanup() {
    local exit_code=$?
    if [[ $exit_code -ne 0 && $exit_reason == completed ]]; then
        exit_reason="failed"
    fi
    docker rm -f "$load_container" >/dev/null 2>&1 || true
    run bash "$project_root/scripts/load/remove-load-data.sh" "$env_file" \
        || printf 'run-server-night: remove-load-data.sh failed, remove LOAD-* data from %s manually\n' "$public_origin" >&2
    write_summary "$exit_code"
    exit "$exit_code"
}
trap cleanup EXIT

printf 'run-server-night: backup -> %s\n' "$backup_dir"
run bash "$project_root/scripts/backup.sh" "$backup_dir" "$env_file"

printf 'run-server-night: seeding load data\n'
run bash "$project_root/scripts/load/seed-load-data.sh" "$env_file"
$dry_run || seeded=true

if $dry_run; then
    printf '[dry-run] would write %s/night.env with PUBLIC_ORIGIN=%s\n' "$state_dir" "$public_origin"
    printf '[dry-run] would run: docker run -d --name %s --network host -v %s:/opt/rtk-crm -w /opt/rtk-crm -e LOAD_HTTP_LOGIN=1 -e LOAD_USERS=%s -e LOAD_DURATION_S=%s -e LOAD_REPORT_BURSTS=%s -e LOAD_SUMMARY_FILE=%s %s node scripts/load/load-test.mjs .load-test/night.env\n' \
        "$load_container" "$project_root" "$load_users" "$load_duration_s" "$load_report_bursts" "$generator_summary" "$node_image"
    printf '[dry-run] would monitor atlas (%s, max %ss) and host free memory (min %sMB) every %ss, stop the container early on breach\n' \
        "$atlas_url" "$atlas_max_s" "$min_free_mb" "$check_interval_s"
    printf '[dry-run] would read %s and exit with code 1 when it is missing or starts with "ошибка генератора:"\n' "$generator_summary"
    exit 0
fi

mkdir -p "$state_dir"
printf 'PUBLIC_ORIGIN=%s\n' "$public_origin" > "$state_dir/night.env"

printf 'run-server-night: starting load generator (%s users, %ss, %s via edge)\n' "$load_users" "$load_duration_s" "$public_origin"
docker run -d --name "$load_container" --network host \
    -v "$project_root:/opt/rtk-crm" -w /opt/rtk-crm \
    -e LOAD_HTTP_LOGIN=1 \
    -e LOAD_USERS="$load_users" \
    -e LOAD_DURATION_S="$load_duration_s" \
    -e LOAD_REPORT_BURSTS="$load_report_bursts" \
    -e LOAD_SUMMARY_FILE="$generator_summary" \
    "$node_image" \
    node scripts/load/load-test.mjs .load-test/night.env >/dev/null
started=true

monitor
generator_exit=$(docker wait "$load_container" 2>/dev/null) || generator_exit=unknown
docker logs "$load_container" > "$log_file" 2>&1 || true
printf 'run-server-night: load generator finished with code %s, log at %s\n' "$generator_exit" "$log_file"

if [[ -s $project_root/$generator_summary ]]; then
    run_result=$(cat "$project_root/$generator_summary")
fi
if [[ -n $abort_reason ]]; then
    exit_reason="ошибка генератора: остановлен досрочно, $abort_reason"
    exit 1
fi
if [[ $generator_exit != 0 || -z $run_result || $run_result == "ошибка генератора:"* ]]; then
    exit_reason=${run_result%%$'\n'*}
    [[ $exit_reason == "ошибка генератора:"* ]] || exit_reason="ошибка генератора: код $generator_exit, итога нет, см. $log_file"
    exit 1
fi
