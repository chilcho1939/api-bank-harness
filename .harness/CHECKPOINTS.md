# CHECKPOINTS — Evaluación del estado final

> Se evalúa el destino, no el camino. El reviewer marca cada casilla con `[x]` o `[ ]` + razón; cualquier casilla vacía bloquea el cierre.

## C1 — El arnés está completo

- [ ] Existen `.harness/AGENTS.md`, `.harness/CHECKPOINTS.md`, `.harness/feature_list.json`, `.harness/progress/current.md`, `.harness/progress/history.md`, `.harness/docs/spec.md`, `.harness/docs/conventions.md`, `.harness/docs/verification.md`, `docs/architecture.md`.
- [ ] Existen `.claude/agents/{leader,implementer,reviewer,explorer}.md` y `.claude/skills/java-l3-standard/SKILL.md`.
- [ ] `./.harness/init.sh` termina con exit 0.

## C2 — El estado es coherente

- [ ] Como mucho **una** feature `in_progress`.
- [ ] Toda feature `done` tiene tests que afirman sus reglas.
- [ ] `current.md` es la plantilla o describe la sesión activa.
- [ ] El último cambio relevante está en `history.md`.

## C3 — El código respeta la arquitectura

- [ ] Paquetes por funcionalidad según `docs/architecture.md`.
- [ ] El test de ArchUnit pasa: `provider/http` no es dependido por el dominio; ningún `@Transactional` envuelve la llamada al proveedor.
- [ ] Sin dependencias prohibidas (`.claude/CLAUDE.md` → "No introducir").
- [ ] Sin `System.out`, `printStackTrace` ni campos con `@Autowired`.
- [ ] Toda dependencia significativa nueva tiene ADR.

## C4 — La verificación es real

- [ ] `./mvnw verify` → `BUILD SUCCESS`, más de 0 tests, ninguno `@Disabled` sin razón.
- [ ] Cada regla de la spec cubierta hasta ahora tiene un test que afirma el resultado concreto.
- [ ] Bordes probados: `1.00`, `1.01`, `10000.00`, `10000.01`, moneda distinta de `MXN`.
- [ ] El proveedor se prueba con WireMock, no con mocks de `RestClient`.

## C5 — La sesión se cerró bien

- [ ] Sin archivos sin trackear sospechosos (`target/`, `*.log`, `.DS_Store` fuera del `.gitignore`).
- [ ] `history.md` tiene la entrada de la última sesión.
- [ ] La última feature quedó `done` o `blocked`, nunca `in_progress` huérfana.
- [ ] Sin reportes `impl_/review_/explore_` sueltos en `.harness/progress/`.
