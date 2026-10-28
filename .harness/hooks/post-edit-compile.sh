#!/usr/bin/env bash
# post-edit-compile.sh — PostToolUse(Edit|Write).
# Compila (main + test) solo si se editó un .java o el pom.xml. Feedback en segundos;
# los tests corren al parar (stop-unit-tests.sh) y la suite completa en init.sh.

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(cd "$SCRIPT_DIR/../.." && pwd)"

FILE="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))' 2>/dev/null)"

case "$FILE" in
  *.java|*/pom.xml) ;;
  *) exit 0 ;;
esac
[ -x ./mvnw ] || { echo "[harness] skip compile (sin mvnw)"; exit 0; }

OUT="$(./mvnw -q test-compile 2>&1)"
if [ $? -eq 0 ]; then
  echo "[harness] compile OK"
else
  echo "[harness] compile ROJO:" >&2
  echo "$OUT" | grep -E "ERROR|error:" | head -15 >&2
  exit 2   # devuelve el error a Claude para que lo corrija
fi
