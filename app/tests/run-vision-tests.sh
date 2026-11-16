#!/usr/bin/env bash
# Vision UI pure-logic checks: compile the app layer's UiLogic
# and the classes it names (against the SDK's android.jar stubs)
# plus the check main, and run it.
#
#   app/tests/run-vision-tests.sh
#
# Moved into the repository on 2026-11-16 (gap inventory A-3);
# before that it lived outside the repo and silently stopped
# compiling at v4.0.0 when NodeSettings/NodeState began to name
# MiningLogic — MiningLogic.java is in the compile list below.
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/workspace/toolchain/android-sdk}"
APP="$(cd "$(dirname "$0")/.." && pwd)"
NODE_SRC="$APP/src/main/java/app/quantusnode/node"
CORE_SRC="$APP/../core/src/main/java/app/quantusnode/node/depin/core"
MAIN="$APP/tests/VisionLogicCheckMain.java"
OUT="${TMPDIR:-/tmp}/visioncheck-$$"
mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -cp "$ANDROID_HOME/platforms/android-35/android.jar" \
  -d "$OUT" \
  "$NODE_SRC/StringsTable.java" \
  "$NODE_SRC/I18n.java" \
  "$NODE_SRC/NodeState.java" \
  "$NODE_SRC/NodeSettings.java" \
  "$NODE_SRC/MiningLogic.java" \
  "$NODE_SRC/UiLogic.java" \
  "$NODE_SRC/PriceLogic.java" \
  "$NODE_SRC/DiagShare.java" \
  "$NODE_SRC/depin/DepinJournalAggregates.java" \
  "$NODE_SRC/depin/DepinIpc.java" \
  "$NODE_SRC/depin/DepinStatus.java" \
  "$NODE_SRC/depin/DepinReconcilePolicy.java" \
  "$NODE_SRC/depin/DepinStatusMerge.java" \
  "$NODE_SRC/depin/DepinGateVerdict.java" \
  "$NODE_SRC/depin/DepinLaunchDerivation.java" \
  "$NODE_SRC/depin/DepinUserIntent.java" \
  "$CORE_SRC/Json.java" \
  "$CORE_SRC/Hex.java" \
  "$CORE_SRC/Ed25519.java" \
  "$CORE_SRC/DepinCore.java" \
  "$CORE_SRC/TransferIo.java" \
  "$NODE_SRC/depin/RpcAttemptLog.java" \
  "$NODE_SRC/depin/DepinTickBreaker.java" \
  "$MAIN"
"$JAVA_HOME/bin/java" -cp "$OUT" VisionLogicCheckMain
