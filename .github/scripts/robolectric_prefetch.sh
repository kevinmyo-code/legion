#!/usr/bin/env bash
# Put Robolectric's android-all jars in <dest> (flat), sha512-verified, so the tests can run with
# robolectric.offline=true and never fetch from Maven Central at test time (#226: Central answered
# 429 and whole runs failed). Usage: robolectric_prefetch.sh <dest-dir>
set -euo pipefail
dest="${1:?usage: robolectric_prefetch.sh <dest-dir>}"
table=".github/robolectric-android-all.txt"
base=https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented

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

for v in $(grep -vE '^(#|version |$)' "$table"); do
  jar="$dest/android-all-instrumented-$v.jar"
  ok=0
  for attempt in 1 2 3; do
    if verified "$jar"; then ok=1; break; fi
    [ "$attempt" = 3 ] && break
    # Corrupt or absent: drop it and fetch again (a bad cached jar must not stick).
    rm -f "$jar" "$jar.sha512"
    for ext in jar.sha512 jar; do
      curl --fail --silent --show-error --location --connect-timeout 20 --max-time 300 \
        --retry 8 --retry-all-errors --retry-delay 20 \
        --output "$dest/android-all-instrumented-$v.$ext" "$base/$v/android-all-instrumented-$v.$ext"
    done
  done
  [ "$ok" = 1 ] || { echo "::error::android-all-instrumented-$v.jar failed its sha512 check after a refetch"; exit 1; }
  echo "ok $v"
done
