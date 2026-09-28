#!/usr/bin/env bash
set -Eeuo pipefail
export MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:-/opt/}"

trap 'printf "moodle-demo: failed at line %s\n" "$LINENO" >&2' ERR

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
crm_env_file=${1:-$project_root/.env.local}
crm_env_file=$(cd "$(dirname "$crm_env_file")" && pwd)/$(basename "$crm_env_file")
moodle_env_file=$project_root/infra/moodle/.env.local
compose_project=${MOODLE_COMPOSE_PROJECT:-rtk-crm-moodle}
crm_moodle_url=${MOODLE_CRM_URL:-http://moodle:8080}

fail() {
    printf 'moodle-demo: %s\n' "$1" >&2
    exit 1
}

new_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 32
    else
        head -c 32 /dev/urandom | base64
    fi | tr '+/' '-_' | tr -d '=\r\n'
}

env_value() {
    [[ -f $1 ]] || return 0
    sed -n "s/^$2=//p" "$1" | tr -d '\r' | tail -n 1
}

write_env() {
    local file=$1 line key pair
    shift
    local -A updates=()
    local order=() content=()
    for pair in "$@"; do
        updates[${pair%%=*}]=${pair#*=}
        order+=("${pair%%=*}")
    done
    if [[ -f $file ]]; then
        while IFS= read -r line || [[ -n $line ]]; do
            line=${line%$'\r'}
            key=${line%%=*}
            if [[ $line == *=* && -n ${updates[$key]+set} ]]; then
                content+=("$key=${updates[$key]}")
                unset "updates[$key]"
            else
                content+=("$line")
            fi
        done < "$file"
    fi
    for key in "${order[@]}"; do
        if [[ -n ${updates[$key]+set} ]]; then
            content+=("$key=${updates[$key]}")
        fi
    done
    (
        umask 077
        printf '%s\n' "${content[@]}" > "$file"
    )
    chmod 600 "$file"
}

moodle_settings=()
for pair in MOODLE_DB_NAME=moodle MOODLE_DB_USER=moodle MOODLE_ADMIN_USERNAME=admin; do
    [[ -n $(env_value "$moodle_env_file" "${pair%%=*}") ]] || moodle_settings+=("$pair")
done
for key in MOODLE_DB_PASSWORD MOODLE_DB_ROOT_PASSWORD MOODLE_ADMIN_PASSWORD MOODLE_JURY_PASSWORD; do
    [[ -n $(env_value "$moodle_env_file" "$key") ]] || moodle_settings+=("$key=$(new_secret)")
done
if ((${#moodle_settings[@]} > 0)); then
    write_env "$moodle_env_file" "${moodle_settings[@]}"
fi

compose() {
    docker compose -p "$compose_project" --env-file "$moodle_env_file" -f "$project_root/infra/moodle/compose.yaml" "$@"
}

compose up -d --wait --wait-timeout 1200

MOODLE_JURY_PASSWORD=$(env_value "$moodle_env_file" MOODLE_JURY_PASSWORD)
export MOODLE_JURY_PASSWORD
setup_output=$(compose exec -T -e MOODLE_JURY_PASSWORD -u daemon moodle /opt/bitnami/php/bin/php \
    < "$project_root/infra/moodle/demo-setup.php") || {
    printf '%s\n' "$setup_output" | grep -v '^TOKEN=' >&2 || true
    fail 'Moodle demo setup script failed'
}
course_ids=$(printf '%s\n' "$setup_output" | sed -n 's/^COURSE_IDS=//p' | tr -d '\r')
token=$(printf '%s\n' "$setup_output" | sed -n 's/^TOKEN=//p' | tr -d '\r')
java_course=$(printf '%s\n' "$setup_output" | sed -n 's/^DEMO_JAVA_COURSE=//p' | tr -d '\r')
data_group=$(printf '%s\n' "$setup_output" | sed -n 's/^DEMO_DATA_GROUP=//p' | tr -d '\r')
teach_course=$(printf '%s\n' "$setup_output" | sed -n 's/^DEMO_TEACH_COURSE=//p' | tr -d '\r')
web_course=$(printf '%s\n' "$setup_output" | sed -n 's/^DEMO_WEB_COURSE=//p' | tr -d '\r')
empty_course=$(printf '%s\n' "$setup_output" | sed -n 's/^DEMO_EMPTY_COURSE=//p' | tr -d '\r')
[[ -n $course_ids && -n $token && -n $java_course && -n $data_group && -n $teach_course && -n $web_course && -n $empty_course ]] \
    || fail 'Moodle demo setup did not report course ids, demo mapping keys and token'

write_env "$crm_env_file" "MOODLE_BASE_URL=$crm_moodle_url" "MOODLE_TOKEN=$token" "MOODLE_COURSE_IDS=$course_ids" \
    "MOODLE_DEMO_JAVA_COURSE=$java_course" "MOODLE_DEMO_DATA_GROUP=$data_group" "MOODLE_DEMO_TEACH_COURSE=$teach_course" \
    "MOODLE_DEMO_WEB_COURSE=$web_course" "MOODLE_DEMO_EMPTY_COURSE=$empty_course"
printf 'Moodle demo is ready at http://localhost:%s (compose project %s), courses %s.\n' \
    "${MOODLE_HTTP_PORT:-8082}" "$compose_project" "$course_ids"
printf 'MOODLE_BASE_URL, MOODLE_TOKEN and MOODLE_COURSE_IDS are written to %s; Moodle credentials are in %s\n' \
    "$crm_env_file" "$moodle_env_file"
printf 'Read-only Moodle account for the jury: crm-jury, password MOODLE_JURY_PASSWORD in %s\n' "$moodle_env_file"
