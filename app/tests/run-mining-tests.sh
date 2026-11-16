#!/usr/bin/env bash
# Plain-JVM harness for the mining feature (v4.0.0), in the shape of
# core/tests/run-tests.sh: javac + java only, no Gradle, no Android
# SDK. It compiles the app module's Android-free mining classes
# together with the string table — and, since v4.1.0, the storage
# rules and the daily track store — and runs MiningHarnessMain.
#
#   app/tests/run-mining-tests.sh
#
# The process tests start real child processes (POSIX shell scripts
# standing in for the miner binary), so the runner needs /bin/sh and
# a temp directory that allows execution (set TMPDIR if /tmp is
# mounted noexec; the affected tests say so and are skipped
# otherwise). Two tests that depend on file permissions are skipped
# when the harness runs as root.
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
APP="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$APP/src/main/java/app/quantusnode/node"
OUT="${TMPDIR:-/tmp}/quantus-mining-tests-$$"
mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT

"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:-options \
  -source 17 -target 17 -d "$OUT" \
  "$SRC/MiningLogic.java" \
  "$SRC/BoundedLogFile.java" \
  "$SRC/MinerSupervisor.java" \
  "$SRC/StorageLogic.java" \
  "$SRC/StorageResetState.java" \
  "$SRC/DailyTrackStore.java" \
  "$SRC/StringsTable.java" \
  "$APP/tests/app/quantusnode/node/MiningHarnessMain.java"

# The three variables below are deliberately put into the harness's
# own environment: the miner reads its whole configuration from
# MINER_* variables as well as from its arguments, and one test
# proves that none of them (nor RUST_LOG) is handed down to the
# miner process. They are test values and configure nothing.
MINER_NODE_ADDR="192.0.2.1:1" MINER_AUTH_TOKEN="from-the-environment" \
RUST_LOG="trace" \
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Djava.io.tmpdir="$OUT" \
  -cp "$OUT" app.quantusnode.node.MiningHarnessMain
