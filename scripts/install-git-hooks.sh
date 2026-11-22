#!/usr/bin/env bash
#
# Installs this repository's git hooks into the local clone: the pre-push hook that refuses commits
# scripts/check-before-push.sh has not passed on.

set -eu

ROOT="$(git rev-parse --show-toplevel)"
HOOKS="$(git rev-parse --git-path hooks)"

mkdir -p "$HOOKS"
cp "$ROOT/scripts/git-hooks/pre-push" "$HOOKS/pre-push"
chmod +x "$HOOKS/pre-push"
echo "Installed the pre-push hook in $HOOKS."
