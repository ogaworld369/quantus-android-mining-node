#!/usr/bin/env bash
# Mechanical i18n check: every language table carries the same
# key set as the Japanese source, and the format placeholders of
# every entry match the source's. (The mining suite's harness
# also counts the keys; this standalone check predates it and is
# kept runnable on its own.)
#
#   app/tests/run-strings-tests.sh
#
# Moved into the repository on 2026-11-16 (gap inventory A-3).
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
APP="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/stringscheck-$$"
mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$OUT" \
  "$APP/src/main/java/app/quantusnode/node/StringsTable.java" \
  "$APP/tests/StringsCheckMain.java"
"$JAVA_HOME/bin/java" -cp "$OUT" StringsCheckMain
