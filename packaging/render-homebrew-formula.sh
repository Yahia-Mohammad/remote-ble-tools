#!/usr/bin/env sh
set -eu

if [ "$#" -ne 4 ]; then
    echo "usage: $0 VERSION ARCHIVE_URL ARCHIVE_SHA256 OUTPUT" >&2
    exit 2
fi

version=$1
archive_url=$2
archive_sha256=$3
output=$4
template=$(dirname "$0")/homebrew/remoteble.rb.template

case "$version" in
    *[!0-9.]* | *..* | .* | *.)
        echo "invalid stable version: $version" >&2
        exit 2
        ;;
esac
if [ "${#archive_sha256}" -ne 64 ] || ! printf '%s' "$archive_sha256" | grep -Eq '^[0-9a-f]{64}$'; then
    echo "invalid SHA-256: $archive_sha256" >&2
    exit 2
fi

mkdir -p "$(dirname "$output")"
sed \
    -e "s|@ARCHIVE_URL@|$archive_url|" \
    -e "s|@VERSION@|$version|" \
    -e "s|@ARCHIVE_SHA256@|$archive_sha256|" \
    "$template" > "$output"
