#!/usr/bin/env bash
# stop-unit-tests.sh — Stop.
# Corre los unitarios (*Test, sin Docker). Los *IT y ArchUnit van en ./.harness/init.sh
# al cerrar la feature. Informativo: no bloquea la parada.

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(cd "$SCRIPT_DIR/../.." && pwd)"

[ -x ./mvnw ] || { echo "[harness] skip tests (sin mvnw)"; exit 0; }

OUT="$(./mvnw -q test 2>&1)"
if [ $? -eq 0 ]; then
  echo "[harness] unitarios OK. Suite completa: ./.harness/init.sh antes de cerrar la feature."
else
  echo "[harness] unitarios ROJOS:"
  echo "$OUT" | grep -E "Tests run:|FAIL|ERROR" | head -15
fi
exit 0
