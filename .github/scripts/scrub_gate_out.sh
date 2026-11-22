#!/usr/bin/env bash
# scrub_gate_out.sh <dir> <allowed file name>...  (gates.yml, review G1, 2026-11-20)
# Run before any gate artifact upload: an artifact on a public repo is readable by any signed-in GitHub
# user and is NOT masked. Fails (and deletes the folder, so nothing is uploaded) when <dir> holds a file
# whose name is not in the allow-list, a subfolder, or text that looks like a key or a credential.
set -euo pipefail
dir=${1:?usage: scrub_gate_out.sh <dir> <allowed names...>}
shift
[ -d "$dir" ] || exit 0
bad() { echo "::error::scrub: $1. Nothing from $dir is uploaded."; rm -rf "$dir"; exit 1; }
while IFS= read -r -d '' f; do
  name=${f#"$dir"/}
  case "$name" in */*) bad "unexpected subfolder file $name";; esac
  ok=0
  for a in "$@"; do [ "$name" = "$a" ] && ok=1; done
  [ "$ok" = 1 ] || bad "$name is not on the allow-list"
done < <(find "$dir" -mindepth 1 \( -type f -o -type l \) -print0)
[ -z "$(find "$dir" -mindepth 1 -type d)" ] || bad "a subfolder"
# Google API keys, bearer/authorization headers, token or key assignments, private key blocks.
if grep -rIlE 'AIza[0-9A-Za-z_-]{20,}|[Bb]earer [A-Za-z0-9._-]{10,}|[Aa]uthorization:|x-goog-api-key|(api[_-]?key|token|secret)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9._-]{16,}|-----BEGIN [A-Z ]*PRIVATE KEY' "$dir" >/dev/null; then
  bad "a file matches a key or credential pattern"
fi
echo "scrub: $(find "$dir" -type f | wc -l) file(s) in $dir, all allowed, no key-like text"
