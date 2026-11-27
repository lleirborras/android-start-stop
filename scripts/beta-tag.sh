#!/bin/sh
# Usage: beta-tag.sh <branch> <latest-stable-tag>   (existing tags on stdin, one per line)
# Prints the next beta tag for a branch: a beta of the version that branch would release (a patch for branches
# that release nothing), numbered after the betas of that version so far.
set -eu

here=$(dirname "$0")
base=$(sh "$here/next-version.sh" "$1" "$2")
if [ -z "$base" ]; then base=$(sh "$here/next-version.sh" fix/beta "$2"); fi
re=$(printf '%s' "$base" | sed 's/\./\\./g')
last=$(grep -E "^${re}-beta\.[0-9]+\$" | sed "s/^${re}-beta\.//" | sort -n | tail -n 1 || true)
echo "${base}-beta.$(( ${last:-0} + 1 ))"
