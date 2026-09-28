#!/usr/bin/env bash
set -Eeuo pipefail

trap 'printf "release-gate: failed at line %s\n" "$LINENO" >&2' ERR

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

step() {
    printf 'release-gate: %s\n' "$1"
}

tmp_env=""
cleanup() {
    [[ -z $tmp_env ]] || rm -f "$tmp_env"
}
trap cleanup EXIT

step 'backend: mvn -q -o test'
(cd "$project_root/backend" && ./mvnw -q -o test)

step 'frontend: npm run build'
(cd "$project_root/frontend" && npm run build)

step 'frontend: npm test'
(cd "$project_root/frontend" && npm test)

tmp_env=$(mktemp)
cat > "$tmp_env" <<'ENV'
CRM_DB_NAME=x
CRM_DB_USER=x
CRM_DB_PASSWORD=x
KEYCLOAK_DB_NAME=x
KEYCLOAK_DB_USER=x
KEYCLOAK_DB_PASSWORD=x
KEYCLOAK_ADMIN_USERNAME=x
KEYCLOAK_ADMIN_PASSWORD=x
POSTGRES_SUPERUSER=x
POSTGRES_SUPERUSER_PASSWORD=x
CRM_OIDC_CLIENT_SECRET=x
PUBLIC_ORIGIN=http://rtk.localhost:8081
TLS_CERTIFICATE_FILE=/dev/null
TLS_CERTIFICATE_KEY_FILE=/dev/null
MOODLE_EDGE_HOST=lms.example.test
ENV

step 'docker compose config (compose.yaml)'
(cd "$project_root" && docker compose --env-file "$tmp_env" config --quiet)

step 'docker compose config (compose.yaml + compose.prod.yaml)'
(cd "$project_root" && docker compose --env-file "$tmp_env" -f compose.yaml -f compose.prod.yaml config --quiet)

step 'docker compose config (compose.yaml + compose.edge.yaml)'
(cd "$project_root" && docker compose --env-file "$tmp_env" -f compose.yaml -f compose.edge.yaml config --quiet)

step 'OK'
