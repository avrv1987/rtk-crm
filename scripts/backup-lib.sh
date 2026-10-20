# Общие функции backup.sh, backup-scheduled.sh и restore-check.sh. Вызывающий скрипт задаёт fail и compose.

# Ключевые таблицы манифеста. Числа снимаются при остановленном backend, поэтому восстановленная копия обязана
# совпасть с ними точно.
BACKUP_COUNT_TABLES='crm_user_profiles organizations contacts interactions interaction_events attachments agreements agreement_files report_jobs learners enrolment_streams partner_requests audit_events'

# Последнее значение ключа из env-файла; окончание строки CRLF не мешает.
env_value() {
    local line value=
    while IFS= read -r line || [[ -n $line ]]; do
        line=${line%$'\r'}
        [[ $line != "$1="* ]] || value=${line#*=}
    done < "$2"
    printf '%s' "$value"
}

# Строка журнала копий backup.log; путь к нему — в переменной log вызывающего скрипта.
log_line() {
    local line
    line="$(date -u +%Y-%m-%dT%H:%M:%SZ) $1"
    if ! { printf '%s\n' "$line" >> "$log"; } 2>/dev/null; then
        printf '%s: %s (backup.log is not writable)\n' "${0##*/}" "$line" >&2
    fi
}

backup_psql() {
    compose exec -T postgres sh -c 'psql --username="$POSTGRES_USER" --dbname="$(printenv "$1")" --no-psqlrc --tuples-only --no-align --set=ON_ERROR_STOP=1 --command="$2"' \
        sh "$1" "$2" < /dev/null
}

# Строки «count.<таблица>=N», «count.keycloak_users=N» и «flyway_version=V».
backup_counts() {
    local sql= table
    for table in $BACKUP_COUNT_TABLES; do
        sql+="SELECT 'count.$table=' || count(*) FROM $table UNION ALL "
    done
    sql+="SELECT 'flyway_version=' || COALESCE((SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1), 'none')"
    backup_psql CRM_DB_NAME "$sql"
    backup_psql KEYCLOAK_DB_NAME "SELECT 'count.keycloak_users=' || count(*) FROM user_entity u JOIN realm r ON r.id = u.realm_id WHERE r.name = 'rtk-crm'"
}

# Число файлов в томе (переменная пути тома из окружения backend), без отметок «Обзора».
backup_file_count() {
    compose run --rm --no-deps -T --entrypoint sh backend -c '
        cd "$(printenv "$1")" && find . -type f ! -path ./.last-backup ! -path ./.last-restore-check | wc -l
    ' sh "$1" < /dev/null | tr -d ' \r'
}

# Отметка для «Обзора»: backup_mark <файл> ok|failed <сообщение> [ключ=значение...]. При сбое сохраняются время и
# размер последнего успеха из прежней отметки. Ошибка записи отметки не портит саму копию: вызывающий её только сообщает.
backup_mark() {
    local name=$1 status=$2 message=$3 now previous
    local -a lines
    shift 3
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    if [[ $status == ok ]]; then
        lines=("status=ok" "at=$now" "ok_at=$now" "$@")
    else
        previous=$(compose run --rm --no-deps -T --entrypoint sh backend -c 'cat "$APP_ATTACHMENTS_STORAGE_ROOT/$1" 2>/dev/null || true' \
            sh "$name" < /dev/null | tr -d '\r') || previous=
        # Отметка прежнего формата — одна строка со временем успешной копии.
        [[ ! $previous =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[^=]*$ ]] || previous="ok_at=$previous"
        lines=("status=failed" "at=$now" "message=${message//$'\n'/ }")
        mapfile -t -O 3 lines < <(grep -E '^(ok_at|bytes|seconds)=' <<< "$previous" || true)
    fi
    printf '%s\n' "${lines[@]}" | compose run --rm --no-deps -T --entrypoint sh backend -c '
        cat > "$APP_ATTACHMENTS_STORAGE_ROOT/$1.tmp" && mv "$APP_ATTACHMENTS_STORAGE_ROOT/$1.tmp" "$APP_ATTACHMENTS_STORAGE_ROOT/$1"
    ' sh "$name"
}
