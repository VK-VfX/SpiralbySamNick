#!/usr/bin/env bash
# Renders every widget on the JVM (no device needed). Usage: scripts/preview.sh [outdir]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-build/preview}"
JSON_JAR="${JSON_JAR:-build/deps/json.jar}"
mkdir -p build/tools "$OUT"
javac -nowarn -d build/tools -encoding UTF-8 -classpath "$JSON_JAR" app/src/main/java/app/skypix/core/*.java tools/app/skypix/tools/*.java 2>&1 | grep -v "Picked up" || true
java -Djava.awt.headless=true -cp "build/tools:$JSON_JAR" app.skypix.tools.Preview "${MODE:-sheets}" "$OUT" 2>&1 | grep -v "Picked up" || true
