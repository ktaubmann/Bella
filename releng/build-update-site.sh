#!/usr/bin/env bash
# Builds and tests Bella including the ADT integration and packs the update
# site into the ZIP given as the first argument. Used by release.yml and
# test-build.yml.
#
# Signs the update site with PGP when BELLA_GPG_KEY (ASCII-armored secret key)
# is set; BELLA_GPG_KEYNAME (fingerprint) and MAVEN_GPG_PASSPHRASE are
# optional. Without the key the update site stays unsigned.
# BELLA_MVN_PROFILES overrides the Maven profiles (default: -Padt).
set -euo pipefail

zip_file=$(realpath -m "${1:?usage: build-update-site.sh <zip file>}")
cd "$(dirname "$0")/.."

sign=""
key_file="${RUNNER_TEMP:-/tmp}/bella-signing-key.asc"
trap 'rm -f "$key_file"' EXIT
if [ -n "${BELLA_GPG_KEY:-}" ]; then
  printf '%s\n' "$BELLA_GPG_KEY" > "$key_file"
  export BELLA_GPG_KEY_FILE="$key_file"
  # Tycho needs a value even for a key without passphrase; it is ignored then.
  export MAVEN_GPG_PASSPHRASE="${MAVEN_GPG_PASSPHRASE:-none}"
  sign="-Psign"
else
  echo "::warning::Secret BELLA_GPG_KEY is not set, the update site is not signed"
fi

runner=()
if command -v xvfb-run >/dev/null; then
  runner=(xvfb-run -a)
fi
"${runner[@]}" ./mvnw -B -ntp ${BELLA_MVN_PROFILES:--Padt} $sign verify

rm -f "$zip_file"
(cd releng/de.kiliantaubmann.bella.updatesite/target/repository && zip -qr "$zip_file" .)
echo "Update site: $zip_file"
