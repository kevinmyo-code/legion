#!/usr/bin/env bash
# Fetch the "Only my voice" speaker model and its Apache-2.0 licence text from their official
# sources and check them against app/speaker-model.pin (cloud-builds step 4). CI only: the laptop
# build copies its own file, and nothing here is committed.
#
#   fetch_speaker_model.sh --check     fetch both files to a temp dir, check size and hashes, keep
#                                      nothing. Exit 0 only when both match the pin.
#   fetch_speaker_model.sh --install   same checks, then REFUSE (exit 3) unless the pin says
#                                      release_ok=yes; only then copy both into app/src/main/assets/.
#
# Run from the repo root. Prints no secret (it needs none).
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
[[ "$sha" =~ ^[0-9a-f]{64}$ ]] || { echo "::error::sha256 in $pin is not 64 hex characters"; exit 1; }
[[ "$lic_blob" =~ ^[0-9a-f]{40}$ ]] || { echo "::error::license_git_blob in $pin is not 40 hex characters"; exit 1; }
case "$url$lic_url" in *[[:space:]]*) echo "::error::a URL in $pin has a space"; exit 1;; esac

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --retry 3 --max-time 600 -o "$tmp/model" "$url"
got_bytes=$(stat -c %s "$tmp/model")
got_sha=$(sha256sum "$tmp/model" | cut -c1-64)
[ "$got_bytes" = "$bytes" ] || { echo "::error::speaker model from $url is $got_bytes bytes, pinned $bytes. Nothing was built."; exit 1; }
[ "$got_sha" = "$sha" ] || { echo "::error::speaker model from $url has sha256 ${got_sha:0:12}..., pinned ${sha:0:12}.... Nothing was built."; exit 1; }
echo "speaker model: $got_bytes bytes, sha256 $got_sha (matches $pin)"

curl -fsSL --retry 3 --max-time 120 -o "$tmp/license" "$lic_url"
got_blob=$(git hash-object --no-filters "$tmp/license")
[ "$got_blob" = "$lic_blob" ] || { echo "::error::licence text from $lic_url has git blob $got_blob, pinned $lic_blob. Nothing was built."; exit 1; }
grep -q "Apache License" "$tmp/license" && grep -q "Version 2.0" "$tmp/license" \
  || { echo "::error::the fetched licence text is not Apache-2.0"; exit 1; }
echo "licence text: git blob $got_blob (matches $pin), Apache License 2.0"

if [ "$mode" = --check ]; then
  echo "check only: nothing installed"
  exit 0
fi

if [ "$release_ok" != yes ]; then
  echo "::error::$pin says release_ok=$release_ok: the official speaker model has not yet been tried on the phone with the enrolled voiceprint (see the comment at the top of $pin). A cloud APK is not built without a speaker model, nor with an untried one. Nothing was built."
  exit 3
fi
dst=app/src/main/assets
mkdir -p "$(dirname "$dst/$path")" "$(dirname "$dst/$lic_path")"
cp "$tmp/model" "$dst/$path"
cp "$tmp/license" "$dst/$lic_path"
echo "installed $dst/$path and $dst/$lic_path"
