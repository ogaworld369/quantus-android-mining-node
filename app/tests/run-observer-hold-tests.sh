#!/usr/bin/env bash
# Observer hold (S-05) checks for the node app: compile
# DepinRuntime (plus what it pulls in, via -sourcepath against
# the android.jar stubs) and the hold check main, and run it.
#
#   app/tests/run-observer-hold-tests.sh
#
# Moved into the repository on 2026-11-16 (gap inventory A-3).
# The retired companion app had a sibling variant of this check;
# it stays with that project's own tree and is not run here.
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/toolchain/jdk-21}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/workspace/toolchain/android-sdk}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
APP="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/observerhold-node-$$"
mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -cp "$ANDROID_JAR" -d "$OUT" \
  -sourcepath "$APP/src/main/java:$APP/../core/src/main/java" \
  "$APP/tests/ObserverHoldCheckMain.java"
"$JAVA_HOME/bin/java" -cp "$OUT:$ANDROID_JAR" app.quantusnode.node.depin.ObserverHoldCheckMain
