#!/usr/bin/env bash
# Put Robolectric's android-all jars in <dest> (flat), sha512-verified, so the tests can run with
# robolectric.offline=true and never fetch from Maven Central at test time (#226: Central answered
# 429 and whole runs failed). Usage: robolectric_prefetch.sh <dest-dir>
set -euo pipefail
dest="${1:?usage: robolectric_prefetch.sh <dest-dir>}"
table=".github/robolectric-android-all.txt"
# Google's mirror of Central first, Central second: Central rate-limits (429) shared runner IPs.
# No curl --retry: it obeys Retry-After, and Central's can be minutes, which hung a run for 20+
# minutes. Rounds and timeouts are explicit below instead.
bases=(https://maven-central.storage-download.googleapis.com/maven2 https://repo1.maven.org/maven2)

want=$(sed -n 's/^robolectric = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
have=$(sed -n 's/^version //p' "$table")
if [ -z "$want" ] || [ "$want" != "$have" ]; then
  echo "::error::$table is for Robolectric '$have' but gradle/libs.versions.toml has '$want'. Update the jar names in the table (see its header)."
  exit 1
fi

mkdir -p "$dest"

# The .sha512 file holds a bare hash. A jar that is missing, empty or differs fails.
verified() { # <jar>
  [ -s "$1" ] && [ -s "$1.sha512" ] &&
    [ "$(sha512sum "$1" | cut -d' ' -f1)" = "$(tr -d ' \r\n' < "$1.sha512")" ]
}

# Fetch <file> of <coordinate> into dest from the first host that answers; 4 rounds over both hosts.
fetch() { # <coordinate> <file>
  local round b
  for round in 1 2 3 4; do
    for b in "${bases[@]}"; do
      echo "fetch $2 from ${b%%/maven2} (round $round)"
      if curl --fail --silent --show-error --location --connect-timeout 15 --max-time 120 \
        --output "$dest/$2" "$b/org/robolectric/android-all-instrumented/$1/$2"; then
        return 0
      fi
    done
    sleep 15
  done
  echo "::error::could not fetch $2 from any host"
  return 1
}

for v in $(grep -vE '^(#|version |$)' "$table"); do
  jar="android-all-instrumented-$v.jar"
  if ! verified "$dest/$jar"; then
    # Absent, or corrupt in the cache: fetch again so a bad jar never sticks.
    rm -f "$dest/$jar" "$dest/$jar.sha512"
    fetch "$v" "$jar.sha512"
    fetch "$v" "$jar"
    verified "$dest/$jar" || { echo "::error::$jar failed its sha512 check after a fresh fetch"; exit 1; }
  fi
  echo "ok $v"
done
