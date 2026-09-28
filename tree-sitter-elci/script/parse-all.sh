#!/usr/bin/env bash
# Parses every .elm file in the repository with this grammar and fails if any tree contains an
# ERROR or MISSING node. The syntax corpus's unsupported and malformed files are skipped: the
# compiler rejects them on purpose.
#
# Usage: script/parse-all.sh [tree-sitter command]   (default: npx --no-install tree-sitter)
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(cd "$here/.." && pwd)"
cd "$here"
if [ "$#" -gt 0 ]; then ts=("$@"); else ts=(npx --no-install tree-sitter); fi

list="$(mktemp)"
trap 'rm -f "$list"' EXIT
find "$repo" -name '*.elm' \
  -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/.git/*' \
  -not -path '*/src/test/resources/syntax/unsupported/*' \
  -not -path '*/src/test/resources/syntax/malformed/*' | LC_ALL=C sort > "$list"

total=$(wc -l < "$list" | tr -d ' ')
failed=0
while IFS= read -r file; do
  if ! output="$("${ts[@]}" parse "$file" 2>&1)"; then
    : # tree-sitter exits non-zero when the tree has errors; the check below reports them
  fi
  if grep -qE '\((ERROR|MISSING)' <<< "$output" || ! grep -q '^(source_file' <<< "$output"; then
    failed=$((failed + 1))
    echo "FAIL ${file#"$repo"/}"
    grep -E '\((ERROR|MISSING)|rror' <<< "$output" | head -5 | sed 's/^/    /'
  fi
done < "$list"

echo "Parsed $total files: $((total - failed)) without errors, $failed with errors."
[ "$failed" -eq 0 ]
