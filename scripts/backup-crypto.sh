backup_key_setup() {
    local line key
    if [[ -z ${BACKUP_ENCRYPTION_KEY_FILE-} && -z ${BACKUP_ENCRYPTION_KEY-} ]]; then
        while IFS= read -r line || [[ -n $line ]]; do
            line=${line%$'\r'}
            case $line in
                BACKUP_ENCRYPTION_KEY_FILE=*) BACKUP_ENCRYPTION_KEY_FILE=${line#*=} ;;
                BACKUP_ENCRYPTION_KEY=*) BACKUP_ENCRYPTION_KEY=${line#*=} ;;
            esac
        done < "$1"
    fi
    command -v openssl >/dev/null 2>&1 || fail 'openssl is not installed'
    if [[ ${2-} == generate && -z ${BACKUP_ENCRYPTION_KEY_FILE-} && -z ${BACKUP_ENCRYPTION_KEY-} ]]; then
        [[ -w $1 ]] || fail "backup encryption key is not set and $1 is not writable: set BACKUP_ENCRYPTION_KEY or BACKUP_ENCRYPTION_KEY_FILE"
        BACKUP_ENCRYPTION_KEY=$(openssl rand -base64 48 | tr -d '\r\n')
        [[ ! -s $1 || -z $(tail -c 1 "$1") ]] || printf '\n' >> "$1"
        printf 'BACKUP_ENCRYPTION_KEY=%s\n' "$BACKUP_ENCRYPTION_KEY" >> "$1"
        printf 'backup: BACKUP_ENCRYPTION_KEY was not set, a new key was added to %s; keep a copy of this file off the server, copies cannot be restored without it\n' "$1" >&2
    fi
    if [[ -n ${BACKUP_ENCRYPTION_KEY_FILE-} ]]; then
        [[ -f $BACKUP_ENCRYPTION_KEY_FILE && -r $BACKUP_ENCRYPTION_KEY_FILE ]] \
            || fail "backup key file $BACKUP_ENCRYPTION_KEY_FILE is not readable"
        [[ $(stat -c %a -- "$BACKUP_ENCRYPTION_KEY_FILE") == [46]00 ]] \
            || fail "backup key file $BACKUP_ENCRYPTION_KEY_FILE must be accessible to its owner only: chmod 600"
        IFS= read -r key < "$BACKUP_ENCRYPTION_KEY_FILE" || true
        backup_pass=file:$BACKUP_ENCRYPTION_KEY_FILE
    else
        key=${BACKUP_ENCRYPTION_KEY-}
        backup_pass=env:BACKUP_ENCRYPTION_KEY
    fi
    (( ${#key} >= 32 )) || fail "backup encryption key is missing or shorter than 32 characters: set BACKUP_ENCRYPTION_KEY in $1 or BACKUP_ENCRYPTION_KEY_FILE"
}

backup_cipher() {
    BACKUP_ENCRYPTION_KEY=${BACKUP_ENCRYPTION_KEY-} openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -pass "$backup_pass" "$@"
}

backup_encrypt() {
    backup_cipher -e -salt
}

backup_decrypt() {
    backup_cipher -d
}

backup_dump_header() {
    set +o pipefail
    backup_decrypt < "$1" 2>/dev/null | head -c 5 | tr -d '\0'
}
