#!/usr/bin/env bash
set -Eeuo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
unset BACKUP_ENCRYPTION_KEY BACKUP_ENCRYPTION_KEY_FILE
export FAKE_OUT=$work/out PATH=$work/bin:$PATH
mkdir -p "$work/bin" "$FAKE_OUT"

cat > "$work/bin/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_OUT/calls"
case $* in
    *pg_dump*CRM_DB_NAME*) printf 'PGDMP crm data' ;;
    *pg_dump*KEYCLOAK_DB_NAME*) printf 'PGDMP keycloak data' ;;
    *APP_REPORTS_STORAGE_ROOT*-cf*) printf 'reports archive' ;;
    *APP_ATTACHMENTS_STORAGE_ROOT*-cf*) printf 'attachments archive' ;;
    *pg_restore*) cat > "$FAKE_OUT/${@: -2:1}" ;;
    *APP_ATTACHMENTS_STORAGE_ROOT*-xf*) cat > "$FAKE_OUT/attachments" ;;
    *APP_REPORTS_STORAGE_ROOT*-xf*) cat > "$FAKE_OUT/reports" ;;
esac
SH
chmod +x "$work/bin/docker"

failures=0
check() {
    if eval "$2"; then
        printf 'ok   %s\n' "$1"
    else
        printf 'FAIL %s\n' "$1"
        failures=$((failures + 1))
    fi
}

key=$(openssl rand -base64 48 | tr -d '\r\n')
printf 'CRM_DB_NAME=rtk_crm\n' > "$work/no-key.env"
printf 'CRM_DB_NAME=rtk_crm\nBACKUP_ENCRYPTION_KEY=%s\n' "$key" > "$work/key.env"
printf 'BACKUP_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 48 | tr -d '\r\n')" > "$work/other.env"
printf 'BACKUP_ENCRYPTION_KEY=short\n' > "$work/short.env"

cp "$work/no-key.env" "$work/generated.env"
check 'backup without a key adds a new key to the env file, warns and encrypts' \
    'bash "$project_root/scripts/backup.sh" "$work/b0" "$work/generated.env" > /dev/null 2> "$work/err" && grep -q "a new key was added" "$work/err" && grep -Eq "^BACKUP_ENCRYPTION_KEY=.{32,}$" "$work/generated.env" && grep -q "^CRM_DB_NAME=rtk_crm$" "$work/generated.env" && [[ -f $work/b0/crm.dump.enc && ! -e $work/b0/crm.dump ]]'
check 'the added key restores that copy and is not added twice' \
    'bash "$project_root/scripts/restore.sh" "$work/b0" "$work/generated.env" > /dev/null && bash "$project_root/scripts/backup.sh" "$work/b0b" "$work/generated.env" > /dev/null 2>&1 && [[ $(grep -c "^BACKUP_ENCRYPTION_KEY=" "$work/generated.env") == 1 ]]'
check 'backup with a short key fails and writes nothing' \
    '! bash "$project_root/scripts/backup.sh" "$work/b1" "$work/short.env" 2>/dev/null && [[ ! -e $work/b1 ]]'
check 'backup with a missing key file fails' \
    '! BACKUP_ENCRYPTION_KEY_FILE=$work/missing.key bash "$project_root/scripts/backup.sh" "$work/b1" "$work/no-key.env" 2>/dev/null'
check 'restore of an encrypted copy without a key fails before touching the stand' \
    ': > "$FAKE_OUT/calls" && ! bash "$project_root/scripts/restore.sh" "$work/b0" "$work/no-key.env" 2>/dev/null && [[ ! -s $FAKE_OUT/calls ]]'

bash "$project_root/scripts/backup.sh" "$work/b2" "$work/key.env" > /dev/null
check 'backup holds only encrypted archives and checksums' \
    '[[ $(cd "$work/b2" && ls | tr "\n" " ") == "SHA256SUMS attachments.tar.enc crm.dump.enc keycloak.dump.enc reports.tar.enc " ]]'
check 'encrypted dump does not contain the plain data' '! grep -aq "PGDMP\|crm data" "$work/b2/crm.dump.enc"'
check 'SHA256SUMS covers the encrypted files' '(cd "$work/b2" && sha256sum --check --quiet SHA256SUMS) && grep -q "crm.dump.enc$" "$work/b2/SHA256SUMS"'

: > "$FAKE_OUT/calls"
check 'restore with another key stops before touching the stand' \
    '! bash "$project_root/scripts/restore.sh" "$work/b2" "$work/other.env" 2> "$work/err" && grep -q "does not match" "$work/err" && [[ ! -s $FAKE_OUT/calls ]]'

bash "$project_root/scripts/restore.sh" "$work/b2" "$work/key.env" > /dev/null
check 'restore decrypts databases and archives' \
    '[[ $(cat "$FAKE_OUT/CRM_DB_NAME") == "PGDMP crm data" && $(cat "$FAKE_OUT/KEYCLOAK_DB_NAME") == "PGDMP keycloak data" && $(cat "$FAKE_OUT/attachments") == "attachments archive" && $(cat "$FAKE_OUT/reports") == "reports archive" ]]'

cp -r "$work/b2" "$work/b3"
printf 'x' >> "$work/b3/attachments.tar.enc"
check 'changed encrypted archive fails the checksum' '! bash "$project_root/scripts/restore.sh" "$work/b3" "$work/key.env" 2>/dev/null'

mkdir "$work/plain"
printf 'PGDMP old crm' > "$work/plain/crm.dump"
printf 'PGDMP old keycloak' > "$work/plain/keycloak.dump"
printf 'old attachments' > "$work/plain/attachments.tar"
(cd "$work/plain" && sha256sum crm.dump keycloak.dump attachments.tar > SHA256SUMS)
bash "$project_root/scripts/restore.sh" "$work/plain" "$work/no-key.env" > /dev/null 2>&1
check 'unencrypted copy made before encryption restores without a key' \
    '[[ $(cat "$FAKE_OUT/CRM_DB_NAME") == "PGDMP old crm" && $(cat "$FAKE_OUT/attachments") == "old attachments" && ! -s $FAKE_OUT/reports ]]'

printf '%s\n' "$key" > "$work/backup.key"
chmod 600 "$work/backup.key"
if [[ $(stat -c %a "$work/backup.key") == 600 ]]; then
    check 'key file with mode 600 is accepted' \
        'BACKUP_ENCRYPTION_KEY_FILE=$work/backup.key bash "$project_root/scripts/backup.sh" "$work/b4" "$work/no-key.env" > /dev/null'
    chmod 644 "$work/backup.key"
    check 'key file readable by others is rejected' \
        '! BACKUP_ENCRYPTION_KEY_FILE=$work/backup.key bash "$project_root/scripts/backup.sh" "$work/b5" "$work/no-key.env" 2>/dev/null'
else
    printf 'skip key file mode checks: file modes are not supported here\n'
fi

((failures == 0)) || { printf 'backup-test: %s check(s) failed\n' "$failures"; exit 1; }
printf 'backup-test: all checks passed\n'
