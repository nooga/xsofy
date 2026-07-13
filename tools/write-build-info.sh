#!/usr/bin/env bash
# Write build-info.json (build provenance for the shell's debug-bar chip) into
# the given dist dir. Shared by `make wasm` and the build-wasm CI action so
# local and Actions builds produce the same manifest: the shell fetches it, and
# the browser smoke gate counts a 404 as a failure. A local `make wasm` that
# skipped this left `make e2e` / `make browser-smoke` failing on the missing
# manifest (nooga/xsofy#116 / #113 review).
set -euo pipefail

dist="${1:?usage: write-build-info.sh <dist-dir>}"

letgo="unknown"
if [ -f .let-go-version ]; then
  letgo="$(awk '!/^[[:space:]]*(#|$)/ {print $1; exit}' .let-go-version)"
fi

# Release version for bug reports (#44): nearest v* tag. Exactly the tag on a
# tagged Pages build (v0.0.2), tag-distance-sha on dev builds. --always keeps
# shallow/tagless clones (CI fetch-depth) from failing the build.
version="$(git describe --tags --match 'v*' --always --dirty 2>/dev/null || echo unknown)"

cat > "$dist/build-info.json" <<JSON
{
  "version": "$version",
  "xsofy": "$(git rev-parse HEAD)",
  "let-go": "$letgo",
  "date": "$(date -u +%Y-%m-%d)"
}
JSON
