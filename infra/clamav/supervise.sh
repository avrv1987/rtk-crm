#!/bin/sh
set -u

rm -f /tmp/clamd.sock /run/clamav/clamd.sock
/init &
init_pid=$!

until [ -S /tmp/clamd.sock ]; do
    kill -0 "$init_pid" 2>/dev/null || exit 1
    sleep 5
done

while pidof clamd >/dev/null; do
    sleep 10
done

echo 'clamd is not running: exiting so that Docker restarts the container' >&2
exit 1
