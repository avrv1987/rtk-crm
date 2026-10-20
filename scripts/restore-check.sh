#!/usr/bin/env bash
# Без -E: ловушка ERR не наследуется подстановками $(...), иначе проверка, ожидаемо упавшая внутри подстановки,
# записала бы ложный сбой. Непредвиденный выход ловит обработчик EXIT.
set -euo pipefail

# Проверка восстановления: копия разворачивается в отдельный временный проект Compose со своими томами и сетью, без
# опубликованных портов, и сверяется с манифестом. Рабочий проект не останавливается и не меняется, кроме отметки
# для «Обзора» администратора в его томе вложений.

log=
copy_name=
marks=
reported=
work=
check_ready=
project=${RESTORE_CHECK_PROJECT:-rtk-crm-restore-check}
started=$SECONDS

report() {
    local message="restore check${copy_name:+ of $copy_name} failed: $1"
    printf 'restore-check: %s\n' "$message" >&2
    [[ -z $log ]] || log_line "RESTORE-CHECK FAILED ${copy_name:-} $1"
    if command -v logger >/dev/null 2>&1; then
        logger -t rtk-crm-backup -p user.err -- "$message" || true
    fi
    if [[ -n $marks ]]; then
        (compose() { production_compose "$@"; }; backup_mark .last-restore-check failed "$1") >> "$log" 2>&1 \
            || log_line 'the failure mark for the admin overview was not written'
    fi
}

fail() {
    report "$1"
    reported=1
    exit 1
}

on_exit() {
    local status=$?
    [[ $status == 0 || -n $reported ]] || report "unexpected error, exit code $status (details in backup.log)"
    if [[ -n $check_ready ]]; then
        check_compose down -v --remove-orphans >> "$log" 2>&1 || log_line "RESTORE-CHECK project $project was not removed"
    fi
    [[ -z $work ]] || rm -rf -- "$work"
}
trap on_exit EXIT

[[ $# -ge 1 && $# -le 2 ]] || fail 'usage: restore-check.sh <backup-root|backup-directory> [env-file]'
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
env_file=${2:-$project_root/.env.local}
[[ -f $env_file ]] || fail "$env_file not found"
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")
source "$project_root/scripts/backup-lib.sh"
[[ -d $1 ]] || fail "$1 is not a directory"
if [[ -f $1/SHA256SUMS ]]; then
    copy=$(cd "$1" && pwd)
    root=$(dirname "$copy")
else
    root=$(cd "$1" && pwd)
    copy=$(find "$root" -mindepth 1 -maxdepth 1 -type d \
        -name '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z' | sort | tail -n 1)
    [[ -n $copy ]] || fail "no copies in $root"
fi
copy_name=${copy##*/}
log=$root/backup.log
backend_image=${RESTORE_CHECK_BACKEND_IMAGE:-rtk-crm-backend}
min_memory_mb=${RESTORE_CHECK_MIN_MEMORY_MB:-3072}

# Рабочий проект — только отметка для «Обзора». Временный — всё остальное; его имя и файлы задаются явно в каждом
# вызове, чтобы ни одна команда временного проекта не ушла в рабочий.
production_compose() {
    (cd "$project_root" && docker compose --env-file "$env_file" "$@")
}
check_compose() {
    # Файлы через -f, а не COMPOSE_FILE: Git Bash на Windows превращает список через «:» в пути с буквой диска.
    (cd "$project_root" && COMPOSE_PROJECT_NAME=$project \
        docker compose -f "$project_root/compose.yaml" -f "$work/compose.restore-check.yaml" --env-file "$work/env" "$@")
}
# backup_psql, backup_counts и backup_file_count из backup-lib.sh работают с временным проектом.
compose() {
    check_compose "$@"
}
marks=1
log_line "RESTORE-CHECK START $copy_name"

[[ $project != "$(env_value COMPOSE_PROJECT_NAME "$env_file")" && $project != rtk-crm ]] \
    || fail "RESTORE_CHECK_PROJECT must differ from the working project"
docker image inspect "$backend_image" > /dev/null 2>&1 \
    || fail "backend image $backend_image not found: set RESTORE_CHECK_BACKEND_IMAGE to the image of the working backend"
# Временный проект занимает около 3 ГБ (postgres, keycloak, разовый backend). Без запаса памяти ядро убило бы рабочий
# контейнер, а не проверочный, поэтому проверка не начинается.
if [[ -r /proc/meminfo ]]; then
    available_mb=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
    [[ -n $available_mb ]] || available_mb=$min_memory_mb
    ((available_mb >= min_memory_mb)) \
        || fail "only $available_mb MB of memory available, $min_memory_mb MB needed (RESTORE_CHECK_MIN_MEMORY_MB)"
fi

umask 077
work=$(mktemp -d)
# Внешние каналы во временном проекте выключены: копия не должна писать в MAX, почту, Moodle и CRM заказчика.
# Пустой SOURCES_SETTINGS_KEY делает нечитаемыми токены, сохранённые на экранах администратора.
grep -v -E '^(COMPOSE_[A-Z_]*|MAX_ENABLED|MAX_BOT_TOKEN|MAIL_ENABLED|MCP_ENABLED|MOODLE_WRITE_ENABLED|MOODLE_WRITE_TOKEN|SOURCES_SYNC_CRON|SOURCES_SETTINGS_KEY|RETENTION_CRON|SITE_BASE_URL|SITE_TOKEN|MOODLE_BASE_URL|MOODLE_TOKEN|CRM_BASE_URL|CRM_TOKEN|LABOR_MARKET_BASE_URL|LABOR_MARKET_TOKEN)=' \
    "$env_file" > "$work/env" || true
cat >> "$work/env" <<'ENV'
MAX_ENABLED=false
MAX_BOT_TOKEN=
MAIL_ENABLED=false
MCP_ENABLED=false
MOODLE_WRITE_ENABLED=false
MOODLE_WRITE_TOKEN=
SOURCES_SYNC_CRON=-
SOURCES_SETTINGS_KEY=
RETENTION_CRON=-
SITE_BASE_URL=
SITE_TOKEN=
MOODLE_BASE_URL=
MOODLE_TOKEN=
CRM_BASE_URL=
CRM_TOKEN=
LABOR_MARKET_BASE_URL=
LABOR_MARKET_TOKEN=
ENV
# Образ backend берётся у рабочего проекта, а не собирается заново; web не запускается, портов наружу нет.
cat > "$work/compose.restore-check.yaml" <<YAML
services:
  web:
    ports: !reset []
  backend:
    image: $backend_image
    pull_policy: never
    mem_limit: 1280m
  postgres:
    mem_limit: 512m
  keycloak:
    mem_limit: 1g
YAML

# Защита рабочего проекта: дальше идут down -v и удаление БД, поэтому имя проекта сверяется по итоговой конфигурации.
[[ $(check_compose config 2>/dev/null | head -n 1) == "name: $project" ]] || fail "compose does not resolve the project name to $project"
check_ready=1
check_compose down -v --remove-orphans >> "$log" 2>&1

restore_started=$SECONDS
# restore.sh получает файлы через COMPOSE_FILE; в Git Bash на Windows список нужен в виде путей Windows через «;».
compose_files="$project_root/compose.yaml:$work/compose.restore-check.yaml" compose_separator=:
if command -v cygpath > /dev/null; then
    compose_files="$(cygpath -w "$project_root/compose.yaml");$(cygpath -w "$work/compose.restore-check.yaml")" compose_separator=';'
fi
(cd "$project_root" && COMPOSE_PROJECT_NAME=$project COMPOSE_PATH_SEPARATOR=$compose_separator \
    COMPOSE_FILE="$compose_files" RESTORE_SERVICES='' \
    bash scripts/restore.sh "$copy" "$work/env") >> "$log" 2>&1 || fail 'restore.sh failed, details in backup.log'
restore_seconds=$((SECONDS - restore_started))

actual=$(backup_counts) || fail 'record counts of the restored databases were not read'
if [[ -f $copy/MANIFEST ]]; then
    mismatches=
    while IFS='=' read -r key expected; do
        case $key in
            files.attachments) value=$(backup_file_count APP_ATTACHMENTS_STORAGE_ROOT) || value=error ;;
            files.reports) value=$(backup_file_count APP_REPORTS_STORAGE_ROOT) || value=error ;;
            count.* | flyway_version) value=$(grep -m 1 "^$key=" <<< "$actual" | cut -d= -f2-) || value=missing ;;
            *) continue ;;
        esac
        [[ $value == "$expected" ]] || mismatches+=" $key=$value(expected $expected)"
    done < "$copy/MANIFEST"
    [[ -z $mismatches ]] || fail "restored data differ from MANIFEST:$mismatches"
else
    log_line "RESTORE-CHECK $copy_name has no MANIFEST (made before manifests): record counts are not compared"
fi

# Вложения и файлы договоров: каждый файл читается целиком и сверяется с контрольной суммой из БД.
attachment_list=$(backup_psql CRM_DB_NAME "SELECT checksum || '  ' || storage_key FROM attachments WHERE status <> 'REJECTED' UNION SELECT checksum || '  ' || storage_key FROM agreement_files WHERE storage_key IS NOT NULL") \
    || fail 'attachment list was not read'
attachments_checked=0
if [[ -n $attachment_list ]]; then
    attachments_checked=$(wc -l <<< "$attachment_list" | tr -d ' ')
    printf '%s\n' "$attachment_list" | check_compose run --rm --no-deps -T --entrypoint sh backend \
        -c 'cd "$APP_ATTACHMENTS_STORAGE_ROOT" && sha256sum --check --quiet -' >> "$log" 2>&1 \
        || fail 'attachment files are missing or differ from their checksums, list in backup.log'
fi

# Готовые отчёты: у каждого задания SUCCEEDED с ключом файла файл есть и читается.
report_keys=$(backup_psql CRM_DB_NAME "SELECT result_storage_key FROM report_jobs WHERE status = 'SUCCEEDED' AND result_storage_key IS NOT NULL") \
    || fail 'report list was not read'
reports_checked=0
if [[ -n $report_keys ]]; then
    reports_checked=$(wc -l <<< "$report_keys" | tr -d ' ')
    missing_reports=$(printf '%s\n' "$report_keys" | check_compose run --rm --no-deps -T --entrypoint sh backend -c '
        cd "$APP_REPORTS_STORAGE_ROOT" && while read -r key; do
            if ! { [ -s "$key" ] && head -c 1 "$key" > /dev/null; }; then echo "$key"; fi
        done') || fail 'report files were not checked'
    [[ -z $missing_reports ]] || fail "$(wc -l <<< "$missing_reports" | tr -d ' ') report files are missing"
fi

# Анкеты: разовый backend с профилем restore-check расшифровывает каждую ключами из .env.local (LearnerRestoreCheck).
learners_output=$(check_compose run --rm --no-deps -T backend --spring.profiles.active=restore-check < /dev/null 2>&1) \
    || { printf '%s\n' "$learners_output" >> "$log"; fail 'learner profiles cannot be decrypted with ENROLMENT_KEYS_* from the env file, details in backup.log'; }
learners_checked=$(sed -n 's/.*RESTORE-CHECK learners.checked=\([0-9]*\).*/\1/p' <<< "$learners_output")
[[ -n $learners_checked ]] || fail 'the backend restore-check run printed no result'

# Вход: восстановленный Keycloak принимает пароль администратора из env-файла, а секрет клиента crm-bff в его БД
# совпадает с CRM_OIDC_CLIENT_SECRET. Секрет передаётся через stdin и в вывод не попадает.
check_compose up -d --wait keycloak >> "$log" 2>&1 || fail 'restored Keycloak did not start'
printf '%s\n' "$(env_value CRM_OIDC_CLIENT_SECRET "$env_file")" | check_compose exec -T keycloak bash -c '
    IFS= read -r expected
    kcadm=/opt/keycloak/bin/kcadm.sh
    "$kcadm" config credentials --server http://localhost:8080 --realm master \
        --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" > /dev/null 2>&1 \
        || { echo "Keycloak administrator password from the env file is refused"; exit 1; }
    id=$("$kcadm" get clients -r rtk-crm -q clientId=crm-bff --fields id --format csv --noquotes)
    secret=$("$kcadm" get "clients/$id/client-secret" -r rtk-crm | sed -n "s/.*\"value\" *: *\"\([^\"]*\)\".*/\1/p")
    [ -n "$secret" ] && [ "$secret" = "$expected" ] || { echo "crm-bff client secret differs from CRM_OIDC_CLIENT_SECRET"; exit 1; }
' >> "$log" 2>&1 || fail 'login check failed, reason in backup.log'

total_seconds=$((SECONDS - started))
summary="restore=${restore_seconds}s total=${total_seconds}s attachments=$attachments_checked reports=$reports_checked learners=$learners_checked"
log_line "RESTORE-CHECK OK $copy_name $summary"
(compose() { production_compose "$@"; }; backup_mark .last-restore-check ok '' "seconds=$restore_seconds") >> "$log" 2>&1 \
    || log_line 'the mark for the admin overview was not written'
printf 'Restore check passed: %s %s\n' "$copy_name" "$summary"
