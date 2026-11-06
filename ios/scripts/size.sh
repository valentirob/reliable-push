#!/usr/bin/env bash
# Size gate: stripped arm64 iOS dynamic library of the SDK must stay under 300 KiB.
set -euo pipefail

LIMIT=307200
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$ROOT/ios/Sources/ReliablePush" -name '*.swift' | sort)

xcrun --sdk iphoneos swiftc -emit-library -O -whole-module-optimization \
  -module-name ReliablePush -target arm64-apple-ios15.0 -swift-version 6 \
  -enable-upcoming-feature ExistentialAny \
  -o "$OUT/libReliablePush.dylib" "${SOURCES[@]}"
strip -x "$OUT/libReliablePush.dylib"

BYTES=$(stat -f %z "$OUT/libReliablePush.dylib")
echo "ReliablePush stripped arm64 dylib: $BYTES bytes (limit $LIMIT)"
if [ "$BYTES" -ge "$LIMIT" ]; then
  echo "size gate FAILED: $BYTES bytes is not under the $LIMIT-byte limit (spec 9.2); shrink the SDK or record a ruling" >&2
  exit 1
fi
