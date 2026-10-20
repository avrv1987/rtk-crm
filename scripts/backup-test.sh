#!/usr/bin/env bash
set -Eeuo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
# В Git Bash mktemp может вернуть путь с буквой диска (D:/...): двоеточие разрезало бы PATH, и вызвался бы
# настоящий docker. Переводим путь в вид /d/... .
if [[ $work == [A-Za-z]:* ]]; then
    if command -v cygpath > /dev/null; then
        work=$(cygpath -u "$work")
    else
        work="/$(tr '[:upper:]' '[:lower:]' <<< "${work:0:1}")${work:2}"
    fi
fi
unset BACKUP_ENCRYPTION_KEY BACKUP_ENCRYPTION_KEY_FILE
export FAKE_OUT=$work/out FAKE_DATA=$work/data PATH=$work/bin:$PATH
mkdir -p "$work/bin" "$FAKE_OUT" "$FAKE_DATA/attachments" "$FAKE_DATA/reports"
printf 'first file' > "$FAKE_DATA/attachments/11111111-1111-1111-1111-111111111111"
printf 'second file' > "$FAKE_DATA/attachments/22222222-2222-2222-2222-222222222222"
printf 'report' > "$FAKE_DATA/reports/33333333-3333-3333-3333-333333333333"

cat > "$work/bin/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_OUT/calls"
case $* in
    *' ps -q'*) printf 'fake-backend\n' ;;
    inspect*) printf 'healthy\n' ;;
    *pg_restore*) cat > "$FAKE_OUT/${@: -2:1}" ;;
    *APP_ATTACHMENTS_STORAGE_ROOT*-xf*) cat > "$FAKE_OUT/attachments" ;;
    *APP_REPORTS_STORAGE_ROOT*-xf*) cat > "$FAKE_OUT/reports" ;;
    *psql*CRM_DB_NAME*) printf 'count.attachments=2\nflyway_version=67\n' ;;
    *psql*KEYCLOAK_DB_NAME*) printf 'count.keycloak_users=3\n' ;;
    *pg_dump*CRM_DB_NAME*) printf 'PGDMP crm data' ;;
    *pg_dump*KEYCLOAK_DB_NAME*) printf 'PGDMP keycloak data' ;;
    *find*APP_ATTACHMENTS_STORAGE_ROOT*) echo $(($(find "$FAKE_DATA/attachments" -type f | wc -l) + ${FAKE_EXTRA_FILES:-0})) ;;
    *find*APP_REPORTS_STORAGE_ROOT*) find "$FAKE_DATA/reports" -type f | wc -l ;;
    *APP_REPORTS_STORAGE_ROOT*-cf*) tar -C "$FAKE_DATA/reports" -cf - . | tee "$FAKE_OUT/reports.sent" ;;
    *APP_ATTACHMENTS_STORAGE_ROOT*-cf*) tar -C "$FAKE_DATA/attachments" -cf - . | tee "$FAKE_OUT/attachments.sent" ;;
    *'cat > '*) cat > "$FAKE_OUT/mark${*: -1}" ;;
    *'cat "$APP_ATTACHMENTS_STORAGE_ROOT/$1"'*) cat "$FAKE_OUT/mark${*: -1}" 2>/dev/null ;;
esac
SH
chmod +x "$work/bin/docker"

# Страховка: тест запускает backup.sh и restore.sh, которые останавливают backend. Если первым в PATH окажется
# не заглушка, а настоящий docker, тест остановил бы рабочий стенд — поэтому без заглушки не запускаемся.
hash -r
if [[ $(command -v docker) != "$work/bin/docker" ]]; then
    printf 'backup-test: docker в PATH — не заглушка теста (%s); тест прерван, настоящий docker не вызывался\n' \
        "$(command -v docker || printf 'не найден')" >&2
    exit 1
fi

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

: > "$FAKE_OUT/calls"
bash "$project_root/scripts/backup.sh" "$work/b2" "$work/key.env" > /dev/null
check 'backup holds only encrypted archives, the manifest and checksums' \
    '[[ $(cd "$work/b2" && LC_ALL=C ls | tr "\n" " ") == "MANIFEST SHA256SUMS attachments.tar.enc crm.dump.enc keycloak.dump.enc reports.tar.enc " ]]'
check 'backend is stopped before the first archive and started after the last file count' \
    'grep -n "" "$FAKE_OUT/calls" > "$work/numbered" && stop=$(grep -m 1 " stop backend" "$work/numbered" | cut -d: -f1) && first=$(grep -m 1 "tar -C" "$work/numbered" | cut -d: -f1) && last=$(grep "find \." "$work/numbered" | tail -n 1 | cut -d: -f1) && start=$(grep -m 1 " start backend" "$work/numbered" | cut -d: -f1) && ((stop < first && last < start))'
check 'MANIFEST holds version, commit, migration, record and file counts and checksums' \
    'grep -Eq "^app_version=[0-9]" "$work/b2/MANIFEST" && grep -q "^commit=" "$work/b2/MANIFEST" && grep -q "^flyway_version=67$" "$work/b2/MANIFEST" && grep -q "^count.keycloak_users=3$" "$work/b2/MANIFEST" && grep -q "^files.attachments=2$" "$work/b2/MANIFEST" && grep -q "^files.reports=1$" "$work/b2/MANIFEST" && grep -q "^sha256.crm.dump.enc=$(sha256sum "$work/b2/crm.dump.enc" | cut -d" " -f1)$" "$work/b2/MANIFEST"'
check 'encrypted dump does not contain the plain data' '! grep -aq "PGDMP\|crm data" "$work/b2/crm.dump.enc"'
check 'SHA256SUMS covers the encrypted files and the manifest' \
    '(cd "$work/b2" && sha256sum --check --quiet SHA256SUMS) && grep -q "crm.dump.enc$" "$work/b2/SHA256SUMS" && grep -q "MANIFEST$" "$work/b2/SHA256SUMS"'
check 'backup leaves the ok mark with the copy size and keeps the marks out of the attachments archive' \
    'grep -q "^status=ok$" "$FAKE_OUT/mark.last-backup" && grep -Eq "^bytes=[0-9]+$" "$FAKE_OUT/mark.last-backup" && grep -Eq "^ok_at=20" "$FAKE_OUT/mark.last-backup" && grep "APP_ATTACHMENTS_STORAGE_ROOT.*-cf" "$FAKE_OUT/calls" | grep -q -- "--exclude=./.last-backup --exclude=./.last-restore-check"'
check 'archive that does not hold every file of the volume fails the copy and starts backend again' \
    ': > "$FAKE_OUT/calls" && ! FAKE_EXTRA_FILES=1 bash "$project_root/scripts/backup.sh" "$work/b2x" "$work/key.env" 2> "$work/err" && grep -q "does not hold the 3 files" "$work/err" && grep -q " start backend" "$FAKE_OUT/calls"'

: > "$FAKE_OUT/calls"
check 'restore with another key stops before touching the stand' \
    '! bash "$project_root/scripts/restore.sh" "$work/b2" "$work/other.env" 2> "$work/err" && grep -q "does not match" "$work/err" && [[ ! -s $FAKE_OUT/calls ]]'

bash "$project_root/scripts/restore.sh" "$work/b2" "$work/key.env" > /dev/null
check 'restore decrypts databases and archives' \
    '[[ $(cat "$FAKE_OUT/CRM_DB_NAME") == "PGDMP crm data" && $(cat "$FAKE_OUT/KEYCLOAK_DB_NAME") == "PGDMP keycloak data" ]] && cmp -s "$FAKE_OUT/attachments" "$FAKE_OUT/attachments.sent" && cmp -s "$FAKE_OUT/reports" "$FAKE_OUT/reports.sent"'

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

command -v flock > /dev/null 2>&1 || { printf '#!/bin/sh\nexit 0\n' > "$work/bin/flock"; chmod +x "$work/bin/flock"; }
mkdir "$work/offsite"
cp "$work/key.env" "$work/scheduled.env"
printf 'BACKUP_KEEP=2\nBACKUP_OFFSITE_DIR=%s\n' "$work/offsite" >> "$work/scheduled.env"
printf '%s\n' "BACKUP_OFFSITE_COMMAND=basename \"\$1\" >> $work/offsite-command" >> "$work/scheduled.env"
for run in 1 2 3; do
    bash "$project_root/scripts/backup-scheduled.sh" "$work/scheduled" '' "$work/scheduled.env" 2>> "$work/scheduled-err" || true
    ((run == 3)) || sleep 1
done
check 'scheduled copies keep BACKUP_KEEP copies here and in BACKUP_OFFSITE_DIR and pass each copy to BACKUP_OFFSITE_COMMAND' \
    '[[ $(find "$work/scheduled" -mindepth 1 -maxdepth 1 -type d -name "20*" | wc -l) == 2 && $(find "$work/offsite" -mindepth 1 -maxdepth 1 -type d -name "20*" | wc -l) == 2 && $(wc -l < "$work/offsite-command") == 3 && $(grep -c " OK " "$work/scheduled/backup.log") == 3 && ! -s $work/scheduled-err ]]'
printf 'BACKUP_OFFSITE_DIR=%s\n' "$work/missing" >> "$work/scheduled.env"
check 'failed transfer off the server fails the run and keeps the time and size of the last copy in the mark' \
    '! bash "$project_root/scripts/backup-scheduled.sh" "$work/scheduled" "" "$work/scheduled.env" 2>/dev/null && grep -q "^status=failed$" "$FAKE_OUT/mark.last-backup" && grep -q "^message=BACKUP_OFFSITE_DIR .* is not a writable directory$" "$FAKE_OUT/mark.last-backup" && grep -Eq "^ok_at=20" "$FAKE_OUT/mark.last-backup" && grep -Eq "^bytes=[0-9]+$" "$FAKE_OUT/mark.last-backup"'

((failures == 0)) || { printf 'backup-test: %s check(s) failed\n' "$failures"; exit 1; }
printf 'backup-test: all checks passed\n'
