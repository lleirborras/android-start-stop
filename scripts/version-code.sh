#!/bin/sh
# Usage: version-code.sh <tag>
# Prints the Android version code for vX.Y.Z or vX.Y.Z-beta.N, ordered like the tags: a beta below its own
# release, and every release below the next one, whatever order the builds ran in.
set -eu

tag="$1"
version="${tag#v}"
beta=99
case "$version" in
    *-beta.*) beta="${version##*-beta.}"; version="${version%-beta.*}" ;;
esac
case "$beta" in
    ''|*[!0-9]*) echo "not a beta number: $tag" >&2; exit 1 ;;
esac
case "$version" in
    *[!0-9.]*|*.*.*.*|.*|*.|*..*) echo "not a vX.Y.Z tag: $tag" >&2; exit 1 ;;
    *.*.*) ;;
    *) echo "not a vX.Y.Z tag: $tag" >&2; exit 1 ;;
esac

IFS=. read -r major minor patch <<VERSION
$version
VERSION

# Leading zeros would read as octal.
minor=$(echo "$minor" | sed 's/^0*//;s/^$/0/')
patch=$(echo "$patch" | sed 's/^0*//;s/^$/0/')
beta=$(echo "$beta" | sed 's/^0*//;s/^$/0/')
major=$(echo "$major" | sed 's/^0*//;s/^$/0/')

if [ "$major" -gt 213 ] || [ "$minor" -gt 999 ] || [ "$patch" -gt 99 ] || [ "$beta" -lt 1 ] || [ "$beta" -gt 99 ]; then
    echo "out of range for a version code: $tag" >&2
    exit 1
fi
if [ "$beta" -eq 99 ] && [ "${tag#*-beta.}" != "$tag" ]; then
    echo "beta number must be below 99: $tag" >&2
    exit 1
fi
echo $(( ((major * 1000 + minor) * 100 + patch) * 100 + beta ))
