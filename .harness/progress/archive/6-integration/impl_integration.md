# Implementación — feature 6 `integration` (RN04, RN05, RN06, RN07, RT02)

Rama `feat/6-integration`. Sin commits. **Sin cambios de producción**: ningún test reveló un bug (`git diff --stat src/main` vacío).

## Archivos

| Archivo | Cambio |
|---|---|
| `src/test/java/mx/gilortiz/transactions/transaction/TransactionFlowIT.java` | **Nuevo.** 19 tests E2E: HTTP real (`RANDOM_PORT` + `RestClient` sobre JDK HttpClient contra `http://localhost:{port}`) → reglas → WireMock → Postgres. Sin MockMvc, sin TestRestTemplate, sin mocks internos. |
| `src/test/java/mx/gilortiz/transactions/PostgresTestConfiguration.java` | **Nuevo.** Un solo contenedor `postgres:17-alpine` por JVM, expuesto como bean `JdbcConnectionDetails`. |
| `src/test/java/mx/gilortiz/transactions/ApplicationContextIT.java` | Usa la config compartida; la aserción `count(*) == 0` pasa a "la tabla existe" (`to_regclass`), porque la BD ahora se comparte. |
| `src/test/java/mx/gilortiz/transactions/OpenApiIT.java`, `transaction/TransactionPersistenceIT.java`, `transaction/TransactionSearchIT.java` | `@Testcontainers` + `@Container @ServiceConnection` → `@Import(PostgresTestConfiguration.class)`. Sin cambios en los tests en sí. |

Dependencias nuevas: ninguna (WireMock, Awaitility, Testcontainers y HikariCP ya estaban). Sin ADR.

### Por qué `JdbcConnectionDetails` y no un bean `@ServiceConnection` del contenedor
Revisé `TestcontainersLifecycleBeanPostProcessor` (Boot 4.1.2, `javap`): llama a `close()` en todo bean `Startable` al destruirse, aunque se use `destroyMethod = ""`. Con varios contextos en caché (por defecto, MockMvc y `RANDOM_PORT`), cerrar uno detendría el contenedor de los demás. Con `JdbcConnectionDetails` Spring no gestiona el ciclo de vida del contenedor: se arranca una vez en un inicializador estático y Ryuk lo elimina. Además, los tres IT con la config por defecto ahora comparten un solo contexto (antes eran tres contextos y tres contenedores).

## Escenarios por acceptance

| Acceptance | Test (`TransactionFlowIT.java:línea`) | Qué afirma |
|---|---|---|
| aprobada persiste EXECUTED con saldo | `approvedIsPersistedAsExecuted` :156 | 201; los campos de la respuesta; fila en BD EXECUTED con `providerTransactionId=txn-789` y `balanceAfter=5500.00`, `code`/`message` null, `createdAt` igual; WireMock recibió el cuerpo literal de la spec. |
| rechazo persiste REJECTED con code | `rejectionIsPersistedWithCodeAndMessage` :188 (×4: 400, 422, 500, 503) | 201 REJECTED; fila con `code=INSUFFICIENT_FUNDS` y `message` literal de la spec; sin `providerTransactionId`/`balanceAfter`. |
| regla violada → 422 y el proveedor no recibe ninguna llamada | `businessRuleViolationNeverReachesProvider` :216 (×4: CREDIT 1.00, DEBIT 1.00, DEBIT 10000.01, USD) | 422 `application/problem+json` con su `code`; journal de WireMock vacío (había un stub que aprobaría); 0 filas. Lado positivo: `boundaryAmountsReachProvider` :232 (CREDIT/DEBIT 1.01, DEBIT 10000.00, CREDIT 10000.01 → 201 EXECUTED, 1 llamada con ese monto). |
| timeout persiste UNKNOWN | `readTimeoutIsPersistedAsUnknown` :246 | 201 UNKNOWN; fila UNKNOWN con `code=PROVIDER_TIMEOUT`; exactamente 1 llamada al proveedor (sin reintento). |
| replay con la misma llave devuelve la misma transacción | `replayReturnsSameTransaction` :270; `sameKeyWithOtherPayloadIsConflict` :287 | Replay: 200 con respuesta idéntica, 1 llamada, 1 fila. Otro payload: 409 `IDEMPOTENCY_KEY_CONFLICT`, sin segunda llamada, la fila original intacta. |
| N concurrentes con la misma llave → una fila y una llamada | `concurrentRequestsWithSameKeyExecuteOnce` :304 | 10 requests liberadas a la vez (`CountDownLatch` ready/start, virtual threads). Con la ganadora retenida en el proveedor, las otras 9 responden: exactamente un 201 EXECUTED y nueve 200 con el mismo id (en PENDING, porque respondieron mientras la ganadora esperaba). 1 fila (EXECUTED), 1 llamada al proveedor. |
| (pendiente de f4) sin conexión retenida durante la llamada | `providerCallHoldsNoDatabaseConnection` :341 | Pool Hikari de **2** y 10 requests con llaves distintas: las **10** están dentro de la llamada al proveedor a la vez; en ese momento hay 10 filas PENDING ya confirmadas (se ven desde otra conexión) y `HikariPoolMXBean.getActiveConnections() == 0`. Después las 10 responden 201 EXECUTED. |
| consulta con filtros y paginación | `searchFiltersAndPaginates` :369 | 5 transacciones creadas por POST (aprobadas y rechazadas, 2 cuentas, 2 tipos). GET por `accountId`, `status`, `type` y los tres combinados; recorrido con `limit=2` en las páginas 0–2: `page`/`limit`/`totalElements=5`/`totalPages=3` y orden `createdAt desc, id desc` sin filas repetidas ni perdidas; la página 3 viene vacía. |

### Prueba de mutación del test de conexiones (hecha y revertida)
Anoté `TransactionService.execute` con `@Transactional` de forma temporal y corrí solo `providerCallHoldsNoDatabaseConnection`: **falla** con `ConditionTimeoutException ... but was: 2 within 3 seconds` (solo llegan al proveedor tantas requests como conexiones tiene el pool). Restauré el archivo desde un respaldo; `git diff src/main` está vacío.

## Determinismo

- **Sin demoras fijas ni sleeps.** El proveedor "lento" es una compuerta: `ProviderGate` (`ResponseTransformerV2`, solo en los stubs que la piden) retiene cada respuesta hasta que el test llama a `open()` y cuenta las llegadas. Los tests esperan **condiciones observables** con Awaitility (`arrivals == N`, `completed == N-1`), sondeando cada 10 ms desde el inicio.
- **Orden de la carrera forzado**: la ganadora sigue retenida hasta que las 9 repeticiones respondieron, así que siempre hay 1×201 y 9×200 en PENDING. El resultado no depende de la planificación de hilos.
- **Holgura de timeouts**: `provider.read-timeout=4s` (el que cuesta el test de timeout); las esperas con peticiones retenidas tienen tope de 3 s (< read-timeout); en local se cumplen en ~100 ms. Tope de seguridad de la compuerta: 30 s; el cliente de prueba usa un read-timeout de 15 s para no colgarse.
- **Aislamiento entre tests**: `@BeforeEach` hace `DELETE FROM transactions`, `PROVIDER.resetAll()` y `GATE.reset()`. `@AfterEach` abre la compuerta y espera a que WireMock registre en el journal las peticiones liberadas (lo hace **después** de responder; sin esa espera, la del test de timeout aparecía en el siguiente, y lo vi fallar así una vez).
- Circuit breaker con `minimum-number-of-calls=100`: el UNKNOWN del timeout nunca lo abre aquí (el circuito se prueba en `HttpTransactionProviderTest`).
- WireMock con `containerThreads(40)` para las 10 peticiones retenidas a la vez.
- Ejecuciones: `init.sh` ×2 (exit 0 las dos) + `TransactionFlowIT` aislado ×3 más (exit 0).

## Duración

- `TransactionFlowIT`: 5.5 s (19 tests; 4.6 s es el test de timeout, el resto ~20–120 ms cada uno).
- Suite IT completa (5 clases, 39 tests): ~11.6 s de tests más el arranque de contextos; `init.sh` completo ≈ 31 s de reloj en local.

## Verificación

```
./.harness/init.sh  → exit 0 (dos veces)
Unitarios (Surefire): 192 tests, 0 fallos — ArchitectureTest 10, HttpTransactionProviderTest 30, ProviderClientConfigurationTest 4,
  ProviderPropertiesTest 18, ExecuteTransactionCommandTest 12, TransactionControllerTest 37, TransactionRulesTest 30,
  TransactionServiceTest 25, TransactionTest 26
Integración (Failsafe): 39 tests, 0 fallos — ApplicationContextIT 3, OpenApiIT 3, TransactionFlowIT 19,
  TransactionPersistenceIT 7, TransactionSearchIT 7
[OK] verify verde (unitarios + integración + ArchUnit)
```
Los `ERROR`/`WARN` que se ven en la salida son logs esperados de tests de camino de error (p. ej. `duplicate key` en la carrera de la llave), no fallos.

## Pre-matriz de reglas

| Regla | Qué exige | Implementación (`archivo:línea`) | Test que la afirma (`archivo:línea`) | Verificación manual (`curl`) |
|---|---|---|---|---|
| RN04 | RN01 falla → el proveedor no recibe la llamada | `TransactionService.java:47` (reglas antes de `:56`/`:61`) | `TransactionFlowIT.java:216` (CREDIT/DEBIT 1.00) + `:225` | `curl -X POST :8080/transactions … "amount":1.00` → 422 `AMOUNT_TOO_LOW`; `curl :8089/__admin/requests` sin llamadas |
| RN04 | RN02 falla → sin llamada | `TransactionService.java:47` | `TransactionFlowIT.java:216` (DEBIT 10000.01) | `… "type":"DEBIT","amount":10000.01` → 422 `DEBIT_AMOUNT_EXCEEDS_LIMIT` |
| RN04 | RN03 falla → sin llamada | `TransactionService.java:47` | `TransactionFlowIT.java:216` (USD) | `… "currency":"USD"` → 422 `UNSUPPORTED_CURRENCY` |
| RN04 | Bordes válidos sí llegan al proveedor (1.01; DEBIT 10000.00; CREDIT sin límite) | `TransactionRules.java` | `TransactionFlowIT.java:232` | `… "amount":1.01` → 201 EXECUTED |
| RN05 | Aprobado persiste `providerTransactionId` | `TransactionService.java:122`, `:99` | `TransactionFlowIT.java:156` (BD) | `curl -X POST … ` → 201 `"providerTransactionId":"txn-789"` |
| RN05 | Aprobado persiste `balanceAfter` | `TransactionService.java:122`, `:99` | `TransactionFlowIT.java:175` | idem → `"balanceAfter":5500.00` |
| RN06 | 4xx con cuerpo → REJECTED con `code` | `HttpTransactionProvider.java:169`, `TransactionService.java:131` | `TransactionFlowIT.java:188` (400, 422) + `:201` | stub 422 → 201 `"status":"REJECTED"`; `psql … SELECT code` |
| RN06 | 5xx con cuerpo → REJECTED con `code` | idem | `TransactionFlowIT.java:188` (500, 503) | stub 500 con cuerpo → 201 REJECTED |
| RN06 | Se persiste `message` | `TransactionService.java:131` | `TransactionFlowIT.java:188` (`stored.message()`) | `SELECT message FROM transactions` |
| RN07 | Filtro `accountId` | `TransactionController.java:96`, `TransactionSearchCriteria` | `TransactionFlowIT.java:369` | `curl ':8080/transactions?accountId=acc-A'` |
| RN07 | Filtro `status` | idem | `TransactionFlowIT.java:369` | `curl ':8080/transactions?status=REJECTED'` |
| RN07 | Filtro `type` | idem | `TransactionFlowIT.java:369` | `curl ':8080/transactions?type=DEBIT'` |
| RN07 | Filtros combinados | idem | `TransactionFlowIT.java:369` | `curl ':8080/transactions?accountId=acc-A&status=EXECUTED&type=CREDIT'` |
| RN07 | Paginación `page`, `limit` estable | `TransactionService.java` (`STABLE_ORDER`), `TransactionController.java:109` | `TransactionFlowIT.java:399` | `curl ':8080/transactions?page=1&limit=2'` |
| RT02 | Proveedor probado con mocks (WireMock), flujo completo | — | `TransactionFlowIT.java` (19 tests) + `HttpTransactionProviderTest` | — |
| B4/timeout | Timeout de lectura → UNKNOWN, sin reintento | `HttpTransactionProvider.java:192`, `TransactionService.java:133` | `TransactionFlowIT.java:246`, `:257` | stub con `fixedDelayMilliseconds` > read-timeout → 201 UNKNOWN |
| C4 | Replay misma llave → misma transacción | `TransactionService.java:46-52` | `TransactionFlowIT.java:270` | `curl -H 'Idempotency-Key: k1'` ×2 → 201 y 200 con el mismo id |
| C5 | Misma llave con otro payload → 409 | `TransactionService.java` (`replay`) | `TransactionFlowIT.java:287`, `:293` | otro `amount` con `k1` → 409 `IDEMPOTENCY_KEY_CONFLICT` |
| C4 | Carrera concurrente → 1 fila, 1 llamada | `V1__create_transactions.sql:18`, `TransactionService.java:56-58`, `:82` | `TransactionFlowIT.java:304`, `:321` | — (solo reproducible con carga concurrente) |
| C3 | Sin conexión retenida durante la llamada | `TransactionService.java:56`/`:61`/`:99` (sin `@Transactional`), `application.yaml:11` | `TransactionFlowIT.java:341`, `:351-352` (+ mutación documentada) | — |

Sin filas `BLOCKED`.

## Auditoría Java L3

| Regla | Estado | Evidencia (`archivo:línea`) | Nota |
|-------|--------|------------------------------|------|
| A1–A7 | N/A | — | Sin cambios de producción. |
| B4 | ✅ | `TransactionFlowIT.java:246-257` | El timeout queda UNKNOWN de punta a punta. |
| C3 | ✅ | `TransactionFlowIT.java:341-352` | Demostrado en ejecución con pool de 2; la mutación con `@Transactional` hace fallar el test. |
| C4 | ✅ | `TransactionFlowIT.java:304-336` | Carrera real: UNIQUE en BD, 1 fila, 1 llamada. |
| C5 | ✅ | `TransactionFlowIT.java:287-299` | 409 por HTTP real. |
| C6 | ✅ | `TransactionFlowIT.java:385-400` | Orden `createdAt desc, id desc` sin repetidos ni faltantes. |
| D2 | ✅ | `TransactionFlowIT.java:258-264` | 1 sola llamada tras el timeout de lectura. |
| E4 | ✅ | `TransactionFlowIT.java:188-193` | 201 aunque el proveedor rechace. |
| E5 | ✅ | `TransactionFlowIT.java:390-396` | `page`/`limit`/`totalElements`/`totalPages` por HTTP. |
| G4 | ✅ | `TransactionFlowIT.java` | Testcontainers + WireMock, flujo completo y carrera concurrente con la misma llave. |
| G6 | ✅ | `TransactionFlowIT.java:211-216`, `:230-232` | 1.00, 1.01, 10000.00, 10000.01 de punta a punta. |
| G7 | ✅ | `@DisplayName` en español en todos los tests | Cada test afirma estado, código HTTP, `code` y número de llamadas. |
| H2 | ✅ | `TransactionFlowIT.java:111-127` | Inyección por constructor, campos `final`. |
| H4 | ✅ | `TransactionFlowIT.java` | Solo comentarios de *por qué* (compuerta, espera del journal, mutación de la tx). |
| H5 | ✅ | — | Sin `System.out`, TODOs ni código muerto. |
| Resto (D1, D3, D4, D5, E1–E3, E6, E7, F1–F3, G1–G3, G5, H1, H3) | N/A | — | Sin cambios; cubiertos en las features 1–5. |

## Decisiones no obvias

1. **Compuerta en lugar de `withFixedDelay`**: lo pide "nada de sleeps ciegos" y hace la carrera determinista.
2. **Las repeticiones concurrentes responden 200 en PENDING**: es el comportamiento ya aceptado en f4/f5 ("duplicado en vuelo recibe la ganadora PENDING"); el test lo fija.
3. **`ApplicationContextIT`**: con la BD compartida, "tabla vacía" ya no es una verdad del arranque; se sustituye por "la tabla existe". El esquema se sigue validando con las otras dos aserciones.
4. **Nombre `TransactionFlowIT`**: es el ejemplo de `conventions.md` para tests de integración.

## Commits sugeridos (Conventional Commits, sin Co-Authored-By)

```
test(it): comparte un solo contenedor Postgres entre todos los IT
test(transaction): agrega IT de punta a punta con HTTP real, WireMock y Postgres (RN04–RN07)
test(transaction): cubre la carrera concurrente de Idempotency-Key y la ausencia de conexión retenida durante el proveedor
```

---

## Vuelta 2: hallazgos de /code-review (`review_integration_codereview.md`)

Sigue sin haber cambios en `src/main` (`git diff --stat src/main` vacío). `./.harness/init.sh` → **exit 0 dos veces** (29 s cada una).

### Corregidos

| # | Hallazgo | Corrección (`archivo:línea`) |
|---|---|---|
| 1 | Poca holgura entre la espera con peticiones retenidas (3 s) y el read-timeout (4 s) | El timeout tiene ahora su propio IT con su propio contexto: `TransactionTimeoutIT.java:41` (`read-timeout=1s`, `connect-timeout=250ms`), test en `:81`. `TransactionFlowIT.java:82` sube a `read-timeout=10s` y `:85` deja `MAX_WAIT_WHILE_HELD=5s`: la espera máxima es la mitad del read-timeout. La suite no se alarga: el escenario de timeout pasa de 4.6 s a ~1 s más el arranque del contexto (`TransactionTimeoutIT` 2.0 s en total). |
| 2 | Orden esperado calculado con `UUID.compareTo` (con signo) | El orden esperado sale de Postgres, `SELECT id … ORDER BY created_at DESC, id DESC` (`TransactionFlowIT.java:342`). No depende del código bajo prueba ni de cómo Java compara UUID. |
| 3 | Inicializador estático opaco | Arranque perezoso en un método `synchronized` que se llama desde el bean (`PostgresTestConfiguration.java:24`, `:50`). Si Docker falla, cada IT muestra la excepción real de Testcontainers en lugar de `ExceptionInInitializerError`/`NoClassDefFoundError`. |
| 6 | `@AfterEach` que esperaba al journal y podía fallar u ocultar el error real | El `@AfterEach` solo abre la compuerta (`TransactionFlowIT.java:132-133`, `TransactionTimeoutIT.java:75-76`): no lanza ni espera nada. Ya no hace falta esperar: en `TransactionFlowIT` cada test consume las respuestas que retuvo antes de terminar, y WireMock registra la petición antes de enviar la respuesta, así que no quedan peticiones sin registrar. El único test que terminaba con una petición todavía retenida (el de timeout) ahora tiene su propio WireMock y un solo test, y verifica la llamada única con el contador de la compuerta, no con el journal. |
| 7 | La compuerta se abría en 3 sitios solapados | Ahora hay un solo responsable por flujo. `sendConcurrently` la abre en su `finally` (`TransactionFlowIT.java:383`) porque necesita recoger las respuestas, incluso cuando `whileHeld` falla. El `@AfterEach` queda como red de seguridad. Los lambdas de los tests ya no la abren. |
| 8 | `COUNT(*)` escrito a mano | Se usa `repository.count()` (`:209`, `:241`, `:258`, `:289`) y `repository.count(spec)` con `TransactionSearchCriteria` para contar por estado (`:309`, `:318`). `JdbcTemplate` solo queda para el orden independiente del #2. |
| 9 | Cada IT limpiaba la tabla a su manera | `DatabaseCleaner` (nuevo, `src/test/java/mx/gilortiz/transactions/DatabaseCleaner.java`) es un bean declarado en `PostgresTestConfiguration`. Lo usan todos los IT que escriben: `TransactionFlowIT:124`, `TransactionTimeoutIT:69`, `TransactionPersistenceIT` y `TransactionSearchIT` (antes `repository.deleteAll()`). `ApplicationContextIT` y `OpenApiIT` no escriben, así que no limpian. |

Refactor de apoyo, solo en tests, para no duplicar código entre los dos IT HTTP:
- `transaction/ProviderGate.java`: la compuerta de WireMock, extraída tal cual.
- `transaction/ProviderStubs.java`: los cuerpos literales de la spec y los builders de los stubs.
- `transaction/TransactionApi.java`: el cliente `RestClient` real contra `http://localhost:{port}`, con `HttpResult`.

### Aceptados (sin cambio)

- **#4 Ryuk**: Testcontainers activa Ryuk por defecto, tanto en local como en GitHub Actions. Ryuk elimina el contenedor compartido al terminar la JVM de Failsafe. Solo quedaría vivo con `TESTCONTAINERS_RYUK_DISABLED=true`, que no se usa en este repo ni en `ci.yml`.
- **#5 `ApplicationContextIT` comprueba "la tabla existe" en vez de "la tabla está vacía"**: con la BD compartida, "vacía" depende del orden de ejecución de las clases y no es estable. Una migración que sembrara filas se detectaría al revisar la migración (`V*.sql`). El esquema se sigue validando con las aserciones del UNIQUE y de los índices.

### #2 extendido a `TransactionSearchIT` (a pedido del leader)

`TransactionSearchIT.pagesAreStableWithTiedCreatedAt` también desempataba con `Transaction::id` (`UUID.compareTo`). Le apliqué el mismo arreglo: el orden esperado sale de Postgres con `SELECT id FROM transactions ORDER BY created_at DESC, id DESC` (`TransactionSearchIT.java:120`), y se afirma que hay 7 ids. `JdbcTemplate` se inyecta por constructor y se eliminó el import de `Comparator`. Con UUID v7 el orden era el mismo, así que el bug era latente; ahora el oráculo no depende de cómo Java compara UUID. `./.harness/init.sh` → exit 0 dos veces; `TransactionSearchIT` 7/7.

### Verificación vuelta 2

```
./.harness/init.sh → exit 0 (dos veces, 29 s cada una)
Unitarios: 192 tests, 0 fallos
Integración: 39 tests, 0 fallos — ApplicationContextIT 3, OpenApiIT 3, TransactionFlowIT 18 (0.6 s),
  TransactionPersistenceIT 7, TransactionSearchIT 7, TransactionTimeoutIT 1 (2.0 s)
[OK] verify verde (unitarios + integración + ArchUnit)
```

### Tabla de auditoría L3: cambios respecto de la vuelta 1
| Regla | Estado | Evidencia | Nota |
|---|---|---|---|
| B4/D2 | ✅ | `TransactionTimeoutIT.java:81-97` | UNKNOWN con `PROVIDER_TIMEOUT` y una sola llamada. |
| C3 | ✅ | `TransactionFlowIT.java:299-319` | Misma prueba. El read-timeout de 10 s quita cualquier riesgo de UNKNOWN bajo la compuerta. |
| C6 | ✅ | `TransactionFlowIT.java:341-354` | Oráculo de orden independiente (Postgres). |
| H2 | ✅ | `TransactionFlowIT.java:100-110`, `TransactionTimeoutIT.java:53-58` | Inyección por constructor. |
| H5 | ✅ | — | Sin duplicación entre los IT HTTP (helpers extraídos). |

### Commits sugeridos (actualizados)

```
test(it): comparte un solo contenedor Postgres y una limpieza común entre todos los IT
test(transaction): agrega IT de punta a punta con HTTP real, WireMock y Postgres (RN04–RN07)
test(transaction): cubre la carrera de Idempotency-Key, la ausencia de conexión retenida y el timeout del proveedor
```
