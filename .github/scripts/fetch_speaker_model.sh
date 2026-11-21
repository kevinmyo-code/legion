#!/usr/bin/env bash
# Put the "Only my voice" speaker model and its Apache-2.0 licence text in place for a cloud APK build,
# checked against app/speaker-model.pin (cloud-builds step 4). CI only: the laptop build copies its own
# file, and nothing here is committed.
#
#   fetch_speaker_model.sh --check     fetch the OFFICIAL model and the licence text to a temp dir,
#                                      check size and hashes, keep nothing. No sign-in needed.
#   fetch_speaker_model.sh --install   the file a build ships, by the pin's release_ok:
#                                        yes: the official file (checked as above);
#                                        no:  the INTERIM copy from gs://$LEGION_APK_BUCKET/<interim_object>
#                                             (needs gcloud signed in), checked by interim size and sha256.
#                                      Then the licence text. Copies both into app/src/main/assets/.
#                                      Any missing or mismatched file: exit 1, nothing installed.
#
# Run from the repo root. Prints no secret.
set -euo pipefail
mode=${1:-}
case "$mode" in --check|--install) ;; *) echo "usage: $0 --check|--install" >&2; exit 2;; esac

pin=app/speaker-model.pin
[ -f "$pin" ] || { echo "::error::$pin not found (run from the repo root)"; exit 1; }
val() {
  local v
  v=$(sed -n "s/^$1=//p" "$pin" | tr -d '\r')
  [ -n "$v" ] || { echo "::error::$pin has no $1="; exit 1; }
  printf '%s' "$v"
}
path=$(val path); url=$(val url); bytes=$(val bytes); sha=$(val sha256)
lic_path=$(val license_path); lic_url=$(val license_url); lic_blob=$(val license_git_blob)
release_ok=$(val release_ok)
[[ "$lic_blob" =~ ^[0-9a-f]{40}$ ]] || { echo "::error::license_git_blob in $pin is not 40 hex characters"; exit 1; }
case "$url$lic_url" in *[[:space:]]*) echo "::error::a URL in $pin has a space"; exit 1;; esac
case "$release_ok" in yes|no) ;; *) echo "::error::release_ok in $pin must be yes or no"; exit 1;; esac

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# check_file <file> <bytes> <sha256> <what>
check_file() {
  [[ "$3" =~ ^[0-9a-f]{64}$ ]] || { echo "::error::the pinned sha256 for $4 is not 64 hex characters"; exit 1; }
  local got_bytes got_sha
  got_bytes=$(stat -c %s "$1")
  got_sha=$(sha256sum "$1" | cut -c1-64)
  [ "$got_bytes" = "$2" ] || { echo "::error::$4 is $got_bytes bytes, pinned $2. Nothing was installed."; exit 1; }
  [ "$got_sha" = "$3" ] || { echo "::error::$4 has sha256 $got_sha, pinned $3. Nothing was installed."; exit 1; }
  echo "$4: $got_bytes bytes, sha256 $got_sha (matches $pin)"
}

if [ "$mode" = --check ] || [ "$release_ok" = yes ]; then
  curl -fsSL --retry 3 --max-time 600 -o "$tmp/model" "$url"
  check_file "$tmp/model" "$bytes" "$sha" "official speaker model"
else
  obj=$(val interim_object)
  bucket=${LEGION_APK_BUCKET:-}
  [ -n "$bucket" ] || { echo "::error::LEGION_APK_BUCKET is not set: the interim speaker model comes from the private APK bucket. Nothing was installed."; exit 1; }
  echo "release_ok=no: INTERIM speaker model (the copy on the phone today) from the private bucket"
  gcloud storage cp "gs://$bucket/$obj" "$tmp/model" \
    || { echo "::error::could not copy gs://$bucket/$obj (not uploaded yet?). Nothing was installed."; exit 1; }
  check_file "$tmp/model" "$(val interim_bytes)" "$(val interim_sha256)" "interim speaker model"
fi

curl -fsSL --retry 3 --max-time 120 -o "$tmp/license" "$lic_url"
got_blob=$(git hash-object --no-filters "$tmp/license")
[ "$got_blob" = "$lic_blob" ] || { echo "::error::licence text from $lic_url has git blob $got_blob, pinned $lic_blob. Nothing was installed."; exit 1; }
grep -q "Apache License" "$tmp/license" && grep -q "Version 2.0" "$tmp/license" \
  || { echo "::error::the fetched licence text is not Apache-2.0"; exit 1; }
echo "licence text: git blob $got_blob (matches $pin), Apache License 2.0"

if [ "$mode" = --check ]; then
  echo "check only: nothing installed"
  exit 0
fi
dst=app/src/main/assets
mkdir -p "$(dirname "$dst/$path")" "$(dirname "$dst/$lic_path")"
cp "$tmp/model" "$dst/$path"
cp "$tmp/license" "$dst/$lic_path"
echo "installed $dst/$path and $dst/$lic_path"
