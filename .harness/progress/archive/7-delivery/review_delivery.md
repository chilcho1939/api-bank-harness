# Review — feature 7 delivery (vuelta 2)

**Veredicto:** APPROVED

**Cobertura:** 16/16 reglas y criterios verificados en PASS.

## Qué cambió desde la vuelta 1 (verificado por mí)

- Comparé `README.md` contra la copia que guardé en la vuelta 1 (`diff /tmp/readme_v1.md README.md`). Solo cambiaron 4 bloques: las líneas 41, 429, 432–433 y 455–456. Ningún bloque `curl`, ninguna salida esperada y ninguna otra sección cambió.
- `find Dockerfile docker-compose.yml wiremock docs/adr -newer /tmp/review_init.log` no devuelve nada: la infraestructura, los stubs y los ADRs no se tocaron desde que corrí mi smoke de la vuelta 1. Por eso esa evidencia de compose y curls sigue siendo válida; se resume abajo.
- `git diff main --stat -- src pom.xml` → vacío.

## Verificación ejecutable

- `./.harness/init.sh` → `EXIT=0`, `[OK] verify verde (unitarios + integración + ArchUnit)`, `[OK] Entorno listo.`
- Totales leídos de los reportes XML: surefire 192 tests, 0 fallos, 0 errores; failsafe 39 tests, 0 fallos, 0 errores.
- Vuelta 1 (sigue vigente porque los archivos no cambiaron): `./mvnw -B verify` → `Tests run: 192` / `Tests run: 39`, `BUILD SUCCESS`. Compose levantó los 3 servicios con health `UP`; `docker compose exec app id` → `uid=10001(spring)`. Los 16 curls del README dieron el status y cuerpo prometidos y el journal de WireMock registró 5 llamadas (RN04).

## Matriz de reglas

| Regla | Qué exige | Cómo lo probé | Evidencia | Resultado |
|-------|-----------|---------------|-----------|-----------|
| ACC-1 multi-stage | builder y runtime | `Dockerfile:4`, `:19`; build en la vuelta 1 | imagen construida | PASS |
| ACC-1 capas Boot | un COPY por capa | `docker history` (vuelta 1) | 62.1MB / 4.1kB / 4.1kB / 73.7kB | PASS |
| ACC-1 no root | usuario no root | `docker compose exec app id` (vuelta 1) | `uid=10001(spring) gid=10001(spring)` | PASS |
| ACC-2 compose | app, postgres, WireMock | `docker compose ps` (vuelta 1) | 3 servicios `Up` | PASS |
| ACC-2 health | UP | `curl -s localhost:8080/actuator/health` (vuelta 1) | `{"groups":["liveness","readiness"],"status":"UP"}` | PASS |
| ACC-3 aprobado | cuerpo de la spec | curl directo al stub (vuelta 1) | `{"transactionId":"txn-…","status":"APPROVED","balance":5500.00,"executedAt":…}` | PASS |
| ACC-3 rechazo | cuerpo literal | curl directo y psql (vuelta 1) | `{"status":"REJECTED","code":"INSUFFICIENT_FUNDS","message":"The account does not have enough balance to complete the transaction"}` | PASS |
| ACC-3 503 sin cuerpo | sin cuerpo | `curl -i` (vuelta 1) | `HTTP/1.1 503`, cuerpo vacío | PASS |
| ACC-3 lento | mayor que el read-timeout | README curl 4 con `time` (vuelta 1) | `201 UNKNOWN` en 3.037 s, 1 sola llamada | PASS |
| ACC-4 curls | literales y funcionando | vuelta 1 + diff de la vuelta 2 | todos dieron lo prometido; el diff no toca ningún bloque de curl | PASS |
| ACC-4 levantar y apagar | instrucción correcta | README:41 contra mi prueba de la vuelta 1 y la semántica de `down -v` | README:41 ahora dice "Apagar: `docker compose down -v`… los datos no sobreviven a `docker compose down` (con o sin `-v`)… `-v` solo evita que queden volúmenes anónimos huérfanos". Coincide con lo que medí (`totalElements: 0` tras `down` + `up`) y con el compose (`docker-compose.yml:43`: el único `volumes:` es el bind mount de WireMock). `docker volume ls -f dangling=true` → 37 volúmenes huérfanos que dejaron `down` sin `-v`: el `-v` sugerido es correcto y útil. No ejecuté `down -v` porque está prohibido para los agentes en `.claude/settings.json`; quien use el README no tiene esa restricción | PASS |
| ACC-4 secciones | todas presentes y fieles | lectura (vuelta 1) + diff | sin cambios fuera de las 3 correcciones | PASS |
| RT01 | persistencia justificada | README:325-340, ADR-0004 | sin cambios | PASS |
| RT04 material de partida | honestidad | README:432-433, :455-456 contra `.harness/docs/reference-config.md:3` | Declara que versiones, dependencias, `application.yaml` y compose base salen de un proyecto previo del autor, "solo configuración y sin código", y enlaza `reference-config.md`. La persona aparece aportándola. El texto coincide con `reference-config.md:3` ("solo configuración (build, runtime, compose). Ningún código") | PASS |
| RT04 Sonar | afirmación verificable | README:429 contra `git log` | "Los issues de código que reportó Sonar se corrigieron en el código, sin desactivar reglas" → commit `3bc65da fix(transaction): corrige hallazgos de Sonar…`. Ya no generaliza a los riesgos de dependencias | PASS |
| RT04 roles y arnés | flujo y rol humano | vuelta 1 (hooks, pre-commit, codereviews, `gh pr list` → mergeó `chilcho1939`) | sin cambios | PASS |
| ACC-5 ADRs 0002–0008 | 7 temas, fieles al código | vuelta 1, con `archivo:línea` del código | archivos sin cambios desde entonces | PASS |

## Estándar Java L3 (filas verificadas por mí)

| Regla | Evidencia | Resultado |
|-------|-----------|-----------|
| D1 timeouts en runtime | `docker-compose.yml:10`; `acc-slow` cierra en 3.037 s | PASS |
| D2 sin retry tras read timeout | journal: 1 request de `acc-slow` | PASS |
| C4/C5 idempotencia | replay → 200 con el mismo id; payload distinto → 409; 1 llamada | PASS |
| C6/E5 paginación | 8c: orden desc, forma `content/page/limit/totalElements/totalPages` | PASS |
| E1/E2 400 frente a 422 | curls 5a–5d | PASS |
| F2 sin propiedades nuevas | el compose solo define variables que `application.yaml` ya lee | PASS |
| H5 sin basura | `git status` sin `target/` ni `*.log` | PASS |

## Checkpoints
- C1: [x] los archivos existen; `init.sh` EXIT=0.
- C2: [x] solo la feature 7 está `in_progress`; `current.md` describe la sesión con el conteo correcto (192 + 39).
- C3: [x] `src/` y `pom.xml` sin diff; ArchUnit en verde; sin dependencias nuevas.
- C4: [x] `BUILD SUCCESS`, 192 + 39 tests, bordes verificados con curl en la vuelta 1.
- C5: [x] dentro del alcance del review. El cierre (history, `done`, archivar `impl_delivery.md` y `review_delivery.md`) lo hace el leader.

## Cambios requeridos
Ninguno.

## Notas (no bloqueantes)
- El conteo 162 → 192 quedó corregido en `impl_delivery.md:38`, `:167` y en `current.md:24`.
- `init.sh:93` (`tail -20`) sigue truncando el desglose por clase. Cambiarlo es decisión del leader: `init.sh` está protegido.
- `docs/sonar-decisiones.md` sigue sin trackear y fuera de los commits sugeridos, como corresponde. Su decisión de subir logback a 1.6.6 no está aplicada en `pom.xml`; es tarea aparte, fuera de la feature 7.
