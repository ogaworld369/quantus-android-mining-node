#!/usr/bin/env bash
# Device-transfer flow verification: compile the whole core
# module plus the app layer's TransferFlow and the verification
# main, and run it. (Distinct from core/tests/run-transfer-tests.sh,
# which is the core protocol suite; this one drives the app-layer
# flow state machine end to end.)
#
#   app/tests/run-transfer-flow-tests.sh
#
# Moved into the repository on 2026-11-16 (gap inventory A-3).
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
APP="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$APP/tests/TransferFlowVerifyMain.java"
OUT="${TMPDIR:-/tmp}/flowverify-node-$$"
mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -d "$OUT" \
  $(find "$APP/../core/src/main/java" -name '*.java') \
  "$APP/src/main/java/app/quantusnode/node/depin/TransferFlow.java" \
  "$MAIN"
"$JAVA_HOME/bin/java" -cp "$OUT" app.quantusnode.node.depin.core.TransferFlowVerifyMain
