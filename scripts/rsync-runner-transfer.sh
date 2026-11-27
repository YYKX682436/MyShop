#!/usr/bin/env bash
set -Eeuo pipefail

(( $# == 3 )) || { echo 'usage: rsync-runner-transfer.sh <api|web> <archive-dir> <full-sha>' >&2; exit 2; }
service=$1 archive_dir=$2 source_sha=$3
[[ "$service" == api || "$service" == web ]] || exit 2
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ -f "$archive_dir/$service.tar" && -f "$archive_dir/$service.record.json" ]] || exit 2
[[ -n "${UPLOAD_KEY:-}" && -n "${UPLOAD_KNOWN_HOSTS:-}" ]] || exit 2

key_dir=$(mktemp -d)
trap 'rm -f "$key_dir/key" "$key_dir/known_hosts"; rmdir "$key_dir"' EXIT
printf '%s\n' "$UPLOAD_KEY" > "$key_dir/key"
printf '%s\n' "$UPLOAD_KNOWN_HOSTS" > "$key_dir/known_hosts"
chmod 0600 "$key_dir/key" "$key_dir/known_hosts"
ssh_command="ssh -p 1429 -i $key_dir/key -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$key_dir/known_hosts -o BatchMode=yes"
started=$SECONDS
timeout --signal=TERM --kill-after=5s 240s rsync -avc --mkpath --partial-dir=.rsync-partial \
  --copy-dest=/basis --stats -e "$ssh_command" \
  "$archive_dir/$service.tar" "$archive_dir/$service.record.json" \
  "myshop-upload@110.42.34.161:/staging/$source_sha/"
printf 'RUNNER_RSYNC service=%s elapsed=%ss\n' "$service" "$((SECONDS - started))"
