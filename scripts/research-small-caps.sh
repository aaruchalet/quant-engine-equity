#!/usr/bin/env bash
# Run the repository's research specification in a fresh read-only Codex session.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"
codex --search -a never exec --ephemeral --skip-git-repo-check --sandbox read-only \
  --output-last-message "$work/report.md" - < "$root/doc/stock.md" >&2
test -s "$work/report.md"
cat "$work/report.md"
