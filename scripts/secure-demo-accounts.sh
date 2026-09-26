#!/usr/bin/env bash
set -Eeuo pipefail
export MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:-/opt/}"

trap 'printf "secure-demo-accounts: failed at line %s\n" "$LINENO" >&2' ERR

fail() {
    printf 'secure-demo-accounts: %s\n' "$1" >&2
    exit 1
}

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
env_file=$project_root/.env.local
accounts_file=$project_root/.demo-accounts.local
if [[ ${1-} == --env-file ]]; then
    [[ $# -ge 2 ]] || fail 'usage: secure-demo-accounts.sh [--env-file <file>] [account-to-keep ...]'
    env_file=$2
    shift 2
fi
[[ -f $env_file ]] || fail "$env_file not found"
env_file=$(cd "$(dirname "$env_file")" && pwd)/$(basename "$env_file")

demo_accounts=(kam-a kam-b kam-c kam-d leader leader-b admin enrol unprofiled unprofiled-2)
declare -A keep=()
for account in "$@"; do
    [[ " ${demo_accounts[*]} " == *" $account "* ]] || fail "$account is not a demo account (${demo_accounts[*]})"
    keep[$account]=1
done
[[ $(sed -n 's/^DEMO_DATA=//p' "$env_file" | tr -d '\r' | tail -n 1) != false ]] \
    || fail 'the installation has no demo accounts (DEMO_DATA=false)'

new_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 24
    else
        head -c 24 /dev/urandom | base64
    fi | tr '+/' '-_' | tr -d '=\r\n'
}

cd "$project_root"

compose() {
    docker compose --env-file "$env_file" "$@"
}

kcadm() {
    compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"
}

compose exec -T keycloak bash -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null

umask 077
passwords=$(mktemp "$project_root/.demo-accounts.XXXXXX")
keep_partial_passwords() {
    if [[ -s $passwords ]]; then
        printf 'secure-demo-accounts: passwords already set for kept accounts are in %s; run the script again to finish\n' "$passwords" >&2
    else
        rm -f -- "$passwords"
        printf 'secure-demo-accounts: run the script again to finish\n' >&2
    fi
}
trap keep_partial_passwords EXIT
enabled=()
disabled=()
for account in "${demo_accounts[@]}"; do
    id=$(kcadm get users -r rtk-crm -q "username=$account" -q exact=true --fields id --format csv --noquotes)
    [[ -n $id ]] || continue
    [[ $id != *$'\n'* ]] || fail "Keycloak user $account is not unique"
    password=$(new_secret)
    printf '{"type":"password","value":"%s","temporary":false}' "$password" \
        | kcadm update "users/$id/reset-password" -r rtk-crm -f -
    if [[ -n ${keep[$account]-} ]]; then
        kcadm update "users/$id" -r rtk-crm -s enabled=true
        printf '%s %s\n' "$account" "$password" >> "$passwords"
        enabled+=("$account")
    else
        kcadm update "users/$id" -r rtk-crm -s enabled=false
        disabled+=("$account")
    fi
    kcadm create "users/$id/logout" -r rtk-crm -s realm=rtk-crm -s "user=$id"
done

session_users=$(printf "'%s'," "${enabled[@]}" "${disabled[@]}")
compose exec -T postgres sh -c 'psql --username="$POSTGRES_USER" --dbname="$CRM_DB_NAME" -v ON_ERROR_STOP=1 -q -c "$1"' sh \
    "DELETE FROM spring_session WHERE principal_name IN (${session_users%,})" >/dev/null

mv -f -- "$passwords" "$accounts_file"
chmod 600 "$accounts_file"
trap - EXIT
if grep -q '^DEMO_ACCOUNTS_SECURED=' "$env_file"; then
    sed -i 's/^DEMO_ACCOUNTS_SECURED=.*/DEMO_ACCOUNTS_SECURED=true/' "$env_file"
else
    [[ -z $(tail -c 1 "$env_file") ]] || printf '\n' >> "$env_file"
    printf 'DEMO_ACCOUNTS_SECURED=true\n' >> "$env_file"
fi
printf 'Demo accounts secured. Enabled with individual passwords: %s. Disabled: %s. Passwords are in %s\n' \
    "${enabled[*]:-none}" "${disabled[*]:-none}" "$accounts_file"
