#!/usr/bin/env bash
# init.sh — Verificación del entorno y suite completa.
#
# Se ejecuta al COMENZAR sesión y antes de declarar una feature `done`.
# Si falla, la sesión no avanza.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

ok()   { printf "${GREEN}[OK]${NC}    %s\n" "$1"; }
warn() { printf "${YELLOW}[WARN]${NC}  %s\n" "$1"; }
fail() { printf "${RED}[FAIL]${NC}  %s\n" "$1"; }

EXIT_CODE=0

echo "── 1. Entorno ──────────────────────────────────────────"

if ! command -v java >/dev/null 2>&1; then
  fail "java no está instalado"; exit 1
fi
JAVA_MAJOR=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
if [ "${JAVA_MAJOR:-0}" -lt 21 ]; then
  fail "Se requiere Java >= 21 (actual: ${JAVA_MAJOR:-desconocido})"; exit 1
fi
ok "java -> $JAVA_MAJOR"

DOCKER_UP=0
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  DOCKER_UP=1; ok "docker en ejecución"
else
  warn "docker no disponible: los *IT (Testcontainers) no pueden correr"
fi

echo ""
echo "── 2. Archivos base del arnés ──────────────────────────"

for f in .harness/AGENTS.md .harness/CHECKPOINTS.md .harness/feature_list.json \
         .harness/progress/current.md .harness/progress/history.md \
         .harness/docs/spec.md .harness/docs/conventions.md .harness/docs/verification.md \
         docs/architecture.md .claude/skills/java-l3-standard/SKILL.md; do
  if [ -f "$f" ]; then ok "Existe $f"; else fail "Falta $f"; EXIT_CODE=1; fi
done

echo ""
echo "── 3. Git hooks ────────────────────────────────────────"

if [ "$(git config --get core.hooksPath || true)" != ".harness/git-hooks" ]; then
  git config core.hooksPath .harness/git-hooks
fi
chmod +x .harness/git-hooks/* .harness/hooks/* 2>/dev/null || true
ok "core.hooksPath -> .harness/git-hooks"

echo ""
echo "── 4. feature_list.json ────────────────────────────────"

python3 - <<'PY'
import json, sys
try:
    data = json.load(open('.harness/feature_list.json'))
    valid = set(data['rules']['valid_status'])
    in_progress = [f for f in data['features'] if f['status'] == 'in_progress']
    if len(in_progress) > 1:
        print(f"[FAIL]  {len(in_progress)} features en in_progress (máximo 1)"); sys.exit(1)
    for f in data['features']:
        if f['status'] not in valid:
            print(f"[FAIL]  Estado inválido en feature {f['id']}: {f['status']}"); sys.exit(1)
    done = sum(f['status'] == 'done' for f in data['features'])
    print(f"[OK]    feature_list.json válido ({done}/{len(data['features'])} done)")
except Exception as e:
    print(f"[FAIL]  feature_list.json inválido: {e}"); sys.exit(1)
PY
[ $? -ne 0 ] && EXIT_CODE=1

echo ""
echo "── 5. Build y tests (./mvnw verify) ────────────────────"

if [ ! -f pom.xml ]; then
  warn "Sin pom.xml: proyecto aún sin bootstrap (feature 1). Skip build."
elif [ $DOCKER_UP -eq 0 ]; then
  warn "Sin Docker: solo unitarios (./mvnw test). No sirve para cerrar una feature."
  ./mvnw -q test 2>&1 | tail -20
  if [ "${PIPESTATUS[0]}" -eq 0 ]; then ok "Unitarios verdes"; else fail "Unitarios rojos"; fi
  EXIT_CODE=1
else
  ./mvnw -q verify 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD" | tail -20
  if [ "${PIPESTATUS[0]}" -eq 0 ]; then ok "verify verde (unitarios + integración + ArchUnit)"; else fail "verify rojo"; EXIT_CODE=1; fi
fi

echo ""
echo "── 6. Resumen ──────────────────────────────────────────"

if [ $EXIT_CODE -eq 0 ]; then
  ok "Entorno listo."
else
  fail "Entorno NO está listo. Resuelve los errores antes de avanzar."
fi
exit $EXIT_CODE
