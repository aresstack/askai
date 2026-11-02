#!/usr/bin/env bash
# Publishes Serp4J into the local Maven repository (~/.m2) so that AskAI can resolve com.aresstack:serp4j-*.
# Serp4J is not on Maven Central yet; see docs/serp4j.md.
#
#   scripts/install-serp4j.sh [ref] [checkout-dir]
#
# ref defaults to SERP4J_REF or "Develop". If checkout-dir (or SERP4J_DIR) already contains a Serp4J checkout,
# it is used as is (no fetch); otherwise Serp4J is cloned there. Serp4J's Gradle needs JDK 17+ to start:
# set SERP4J_JAVA_HOME (falls back to JAVA_HOME_21_X64, then JAVA_HOME).
set -euo pipefail

REF="${1:-${SERP4J_REF:-Develop}}"
DIR="${2:-${SERP4J_DIR:-$(dirname "$0")/../build/serp4j-src}}"
REPO="${SERP4J_REPO:-https://github.com/aresstack/Serp4J.git}"

if [ ! -x "$DIR/gradlew" ]; then
  rm -rf "$DIR"
  git clone --quiet --depth 1 --branch "$REF" "$REPO" "$DIR"
fi

JH="${SERP4J_JAVA_HOME:-${JAVA_HOME_21_X64:-${JAVA_HOME:-}}}"
cd "$DIR"
echo "Serp4J $(git rev-parse --short HEAD 2>/dev/null || echo '?') -> mavenLocal"
if [ -n "$JH" ]; then
  JAVA_HOME="$JH" ./gradlew --no-daemon -q publishToMavenLocal
else
  ./gradlew --no-daemon -q publishToMavenLocal
fi
