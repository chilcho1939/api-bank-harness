# Review — feature 6 integration (vuelta 2)

**Veredicto:** APPROVED

**Cobertura:** 22/22 reglas/acceptance + 9/9 hallazgos de `/code-review` (7 corregidos, 2 aceptados con justificación) verificados.

Rama `feat/6-integration`, sin commits. `git diff --stat main -- src/main pom.xml` → vacío (antes y después de la mutación). Como `src/main` no cambió desde la vuelta 1, el smoke HTTP de esa vuelta (app real + compose + `__admin/requests`) sigue siendo válido: RN04 → 0 llamadas al proveedor tras 3×422; RN05/RN06/UNKNOWN persistidos (`psql`); replay 200 / conflicto 409; 10 `curl` paralelos con la misma llave → 1×201 + 9×200, 1 llamada, 1 fila; RN07 filtros/paginación 200.

## Verificación ejecutable (propia, vuelta 2)

| Corrida | Comando | Resultado |
|---|---|---|
| 1 | `./mvnw -B verify` | Surefire `Tests run: 192, Failures: 0`; Failsafe `Tests run: 39, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`, 28.1 s |
| 2 | `./mvnw -B verify` | igual: 192 + 39, `BUILD SUCCESS`, 27.7 s |
| 3 | `./.harness/init.sh` | exit 0, `Entorno listo` |
| 4–8 | `-Dit.test=TransactionFlowIT,TransactionTimeoutIT` ×5 | `Tests run: 19, Failures: 0` las 5; `duplicate key` 8–9 veces en el log de cada corrida (la rama UNIQUE de la carrera se ejecuta) |
| Mutación | `@Transactional` temporal en `TransactionService.java:46`, solo `TransactionFlowIT` | `providerCallHoldsNoDatabaseConnection` **falla**: `ConditionTimeoutException … expected: 10 but was: 2 within 5 seconds`. Restaurado desde respaldo; diff de `src/main` vacío |

Duración por clase: `TransactionFlowIT` 18 tests en 0.7–0.9 s (antes 5.4 s); `TransactionTimeoutIT` 1 test en 2.0 s; suite completa ~28 s. Cobertura de escenarios conservada: 19 de la vuelta 1 = 18 (Flow) + 1 (Timeout).

## Matriz de reglas

| Regla | Qué exige | Test (`archivo:línea`) | Evidencia | Resultado |
|-------|-----------|------------------------|-----------|-----------|
| RN04 | RN01–RN03 fallan → proveedor sin llamada | `TransactionFlowIT.java:191-210` (×4); `:208` `getAllServeEvents()` vacío con stub que aprobaría; `:209` `repository.count()==0` | verde ×7 | PASS |
| RN04-borde | 1.01 / DEBIT 10000.00 / CREDIT 10000.01 llegan | `TransactionFlowIT.java:212-225` verify(1) con monto literal | verde | PASS |
| RN05 | `providerTransactionId` y `balanceAfter` persistidos | `TransactionFlowIT.java:139-166` (fila BD `:155-161`, cuerpo al proveedor `:163`) | verde | PASS |
| RN06 | 4xx/5xx → `code` y `message` | `TransactionFlowIT.java:168-189` ×(400,422,500,503), `:184-185` | verde | PASS |
| RN07 | filtros y `page`/`limit` | `TransactionFlowIT.java:322-355`: filtros sueltos y combinados, 3 páginas con metadatos, orden contra `ORDER BY created_at DESC, id DESC` de Postgres (`:341`), página 3 vacía | verde | PASS |
| RT02 | proveedor con mocks | WireMock in-process en ambos IT (`TransactionFlowIT.java:96`, `TransactionTimeoutIT.java:46`) | — | PASS |
| Acc-1 | EXECUTED con saldo / REJECTED con code | `:139`, `:171` | BD | PASS |
| Acc-2 | 422 sin llamada al proveedor | `:199`, `:208` | journal vacío | PASS |
| Acc-3 | timeout → UNKNOWN | `TransactionTimeoutIT.java:79-97`: 201, fila UNKNOWN/`PROVIDER_TIMEOUT`, `GATE.arrivals()==1` (un reintento llegaría antes de la respuesta de la API, porque solo se reintenta con backoff ~200 ms < respuesta) | verde ×7 | PASS |
| Acc-4 | replay misma transacción | `TransactionFlowIT.java:227-242` (+409 en `:244-259`) | verde | PASS |
| Acc-5 | N concurrentes misma llave → 1 fila, 1 llamada | `TransactionFlowIT.java:261-294`: 10 virtual threads con latch, `repository.count()==1`, `GATE.arrivals()==1`, `PROVIDER.verify(1,…)` | verde ×7 | PASS |
| Acc-6 | consulta con filtros y paginación | `:322` | ver RN07 | PASS |
| Foco: HTTP real | RANDOM_PORT + RestClient, sin MockMvc/mocks | `TransactionApi.java:29-36` (JDK HttpClient → `localhost:{port}`); `grep MockMvc\|@MockitoBean` en los IT HTTP → nada | lectura | PASS |
| Foco: sin conexión retenida | observable; falla con `@Transactional` | `TransactionFlowIT.java:296-320` (pool 2, 10 en el proveedor, 10 PENDING confirmadas, `getActiveConnections()==0`) | mutación: `expected 10 but was 2` | PASS |
| Foco: sin sleeps | gate + Awaitility | `grep -rn "Thread.sleep\|fixedDelay" src/test/java` → vacío; `awaitAtMost` `:405-407` | — | PASS |
| Foco: src/main intacto | — | diff vacío | — | PASS |

## Hallazgos de `/code-review`

| # | Decisión | Verificación propia | Resultado |
|---|---|---|---|
| 1 holgura | Corregido | `TransactionTimeoutIT.java:35-41` (read-timeout 1s, contexto propio); `TransactionFlowIT.java:82` read-timeout 10s y `:85` espera máx. 5s (mitad) | PASS |
| 2 orden UUID | Corregido (+ SearchIT) | `TransactionFlowIT.java:341-343` y `TransactionSearchIT` (diff: `jdbcTemplate.queryForList(... ORDER BY created_at DESC, id DESC)` + `hasSize(7)`); sin `Comparator`/`UUID.compareTo` | PASS |
| 3 init estático | Corregido | `PostgresTestConfiguration.java:20-24`, `:50-57` (`synchronized`, perezoso desde el bean) | PASS |
| 4 Ryuk | Aceptado | justificado en `impl_integration.md` (Ryuk activo por defecto; no se desactiva en el repo) | PASS |
| 5 tabla vacía | Aceptado | `ApplicationContextIT` conserva Flyway V1, UNIQUE e índices | PASS |
| 6 `@AfterEach` | Corregido | `TransactionFlowIT.java:131-134`, `TransactionTimeoutIT.java:74-77` solo abren la compuerta. Todo test de Flow consume sus respuestas antes de terminar (`sendConcurrently` hace `future.get` de todas, `:386-388`); 7 corridas verdes sin contaminación del journal entre tests | PASS |
| 7 gate en 3 sitios | Corregido | apertura solo en `sendConcurrently` `finally` (`:379-384`) y en `@AfterEach` como red de seguridad | PASS |
| 8 `COUNT(*)` | Corregido | `repository.count()` `:209,241,258,289`; `repository.count(spec)` `:309,318` | PASS |
| 9 limpieza común | Corregido | `DatabaseCleaner.java:16-18` como bean en `PostgresTestConfiguration.java:43-46`; usado por Flow `:124`, Timeout `:69`, Persistence y Search (diff: `repository.deleteAll()` → `databaseCleaner.clean()`) | PASS |

## Estándar Java L3 (filas verificadas por mí)

| Regla | Evidencia | Resultado |
|-------|-----------|-----------|
| C3 | mutación propia → falla `providerCallHoldsNoDatabaseConnection`; ArchitectureTest verde (en las 192 de Surefire) | PASS |
| C4 | `TransactionFlowIT.java:289-293` (1 fila, 1 llamada); `duplicate key` en el log de las 5 corridas | PASS |
| B4/D2 | `TransactionTimeoutIT.java:89-96` UNKNOWN + `PROVIDER_TIMEOUT` + 1 llegada | PASS |
| G4 | Testcontainers + WireMock + carrera con la misma llave | PASS |
| G6 | `TransactionFlowIT.java:193-196`, `:213` | PASS |
| G7 | `@DisplayName` en español; aserciones de status, `code`, BD y WireMock | PASS |
| H2 | constructores `TransactionFlowIT.java:106-115`, `TransactionTimeoutIT.java:54-60`, `DatabaseCleaner.java:12`; campos `final` | PASS |
| H5 | `grep System.out\|printStackTrace\|@Disabled src/test/java` → vacío; helpers sin duplicación (`ProviderGate`, `ProviderStubs`, `TransactionApi`) | PASS |

## Checkpoints
- C1: [x] `init.sh` exit 0; archivos del arnés presentes.
- C2: [x] solo la feature 6 `in_progress`; `current.md` describe la sesión.
- C3: [x] ArchUnit verde; `pom.xml` sin diff, sin dependencias nuevas ni prohibidas; sin `System.out` ni campos `@Autowired`.
- C4: [x] `BUILD SUCCESS` ×2, 192 + 39 tests, ningún `@Disabled`; bordes 1.00/1.01/10000.00/10000.01/USD de punta a punta; proveedor con WireMock.
- C5: [x] para el alcance del review (sin `target/`/`*.log` sin trackear). Los pasos de cierre (history, `done`, archivar `impl_`/`review_`/`review_integration_codereview.md`) los hace el leader.

## Cambios requeridos
Ninguno.

## Notas (no bloqueantes)
1. `docs/sonar-decisiones.md` sigue sin trackear y no pertenece a la feature 6: fuera de sus commits.
2. El orden esperado ahora sale de la misma BD con el mismo `ORDER BY` que usa el código. Sigue siendo independiente del código Java, pero si al código le faltara el desempate por `id`, Postgres podría devolver los empates en el mismo orden de heap y el test no lo detectaría. Lo mismo pasaba antes con UUID v7, así que no es una regresión. El desempate ya lo fija `TransactionServiceTest`/`STABLE_ORDER`.
3. `current.md` sigue mencionando "`@ServiceConnection`" en el plan; ajústalo al cerrar.

## Commits sugeridos (sin Co-Authored-By)
```
test(it): comparte un solo contenedor Postgres y una limpieza común entre todos los IT
test(transaction): agrega IT de punta a punta con HTTP real, WireMock y Postgres (RN04–RN07)
test(transaction): cubre la carrera de Idempotency-Key, la ausencia de conexión retenida y el timeout del proveedor
```
