#!/bin/sh
# Usage: next-version.sh <merged-branch> [<latest-tag>]
# Prints the next release tag for a merged branch, or nothing if that branch type does not release.
#   breaking/, major/        -> major
#   feat/, feature/          -> minor
#   fix/, perf/              -> patch
#   anything else (ci/, docs/, chore/, refactor/, test/, renovate/, ...) -> no release
set -eu

branch="$1"
latest="${2:-v0.0.0}"

case "$branch" in
    breaking/*|major/*) bump="major" ;;
    feat/*|feature/*) bump="minor" ;;
    fix/*|perf/*) bump="patch" ;;
    *) exit 0 ;;
esac

version="${latest#v}"
case "$version" in
    *[!0-9.]*|*.*.*.*|.*|*.|*..*) echo "not a vX.Y.Z tag: $latest" >&2; exit 1 ;;
    *.*.*) ;;
    *) echo "not a vX.Y.Z tag: $latest" >&2; exit 1 ;;
esac

IFS=. read -r major minor patch <<VERSION
$version
VERSION

case "$bump" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
esac

echo "v$major.$minor.$patch"
