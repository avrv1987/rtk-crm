#!/usr/bin/env sh
set -eu

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname postgres \
  --set=crm_db_name="$CRM_DB_NAME" \
  --set=crm_db_user="$CRM_DB_USER" \
  --set=crm_db_password="$CRM_DB_PASSWORD" \
  --set=keycloak_db_name="$KEYCLOAK_DB_NAME" \
  --set=keycloak_db_user="$KEYCLOAK_DB_USER" \
  --set=keycloak_db_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'crm_db_user', :'crm_db_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'crm_db_user')
\gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'keycloak_db_user', :'keycloak_db_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'keycloak_db_user')
\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'crm_db_name', :'crm_db_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'crm_db_name')
\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'keycloak_db_name', :'keycloak_db_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'keycloak_db_name')
\gexec
SQL
