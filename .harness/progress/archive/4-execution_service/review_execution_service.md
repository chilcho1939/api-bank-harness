# Review: feature 4 execution_service (RN04, RN05, RN06), vuelta 2

**Veredicto:** APPROVED

**Cobertura:** 21/21 reglas verificadas. Son los 8 criterios de aceptación originales, el foco crítico y las correcciones de la vuelta 2.

## Verificación ejecutable (propia)

- `./mvnw -B verify`:
  - Surefire: `Tests run: 153, Failures: 0, Errors: 0, Skipped: 0`. De ellos, ExecuteTransactionCommandTest 12, TransactionTest 26, TransactionServiceTest 24 y ArchitectureTest 9.
  - Failsafe: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`. De ellos, TransactionPersistenceIT 7 y ApplicationContextIT 3.
  - Resultado: `BUILD SUCCESS`.
- `./.harness/init.sh` → `INIT_EXIT=0`, con `[OK] verify verde (unitarios + integración + ArchUnit)` y `[OK] Entorno listo.`
- `grep -rn Transactional src/main`: solo aparece en `TransactionRepository.java:22`, sobre el método corto `completePending`, y en el javadoc de `TransactionService.java:13`. El servicio no tiene proxy tx ni aspectos.
- V1 sigue intacta y no hay V2. Se mantienen `open-in-view: false` y `ddl-auto: validate`.
- No hay curl ni docker compose porque la feature no expone HTTP (es la feature 5). En la vuelta 1, el sandbox denegó un IT de sondeo fuera del repo. Los límites de transacción se prueban por estructura, con ArchUnit y con InOrder.

## Matriz de reglas

| Regla | Qué exige | Cómo lo probé | Evidencia | Resultado |
|-------|-----------|---------------|-----------|-----------|
| RN04 / AC1 | Si una regla falla, el proveedor no se llama | `TransactionServiceTest.java:68-78` (1.00, DEBIT 10000.01, USD y 1.00 con llave) | Afirma la `violation` concreta, `verifyNoInteractions(provider)` y `verifyNoInteractions(repository)` | PASS |
| AC2 / C2 | PENDING antes del proveedor y resultado después | `TransactionServiceTest.java:80-97`: InOrder `saveAndFlush` → `provider.execute(ProviderRequest literal)` → `completePending`, con estado PENDING al insertar y al llamar | Verde. Por estructura, `TransactionService.java:50` (tx corta del repo, con commit) va antes de `:55` (sin tx) | PASS |
| RN05 | EXECUTED con `providerTransactionId` y `balanceAfter` | `TransactionServiceTest.java:99-…` (`completePending(…, EXECUTED, "txn-789", 5500.00, …)`), `TransactionPersistenceIT` roundtrip | Verde | PASS |
| RN06 | REJECTED con `code` y `message` | `TransactionServiceTest.java:123-143` [REJECTED] (`verify completePending(null, expected, null, null, code, message)`), IT de rechazo | Verde | PASS |
| AC3 | FAILED y UNKNOWN con sus datos | `TransactionServiceTest.java:123-143` [FAILED], [UNKNOWN]; `switch` exhaustivo en `TransactionService.java:104-121` | Verde | PASS |
| Transición única (entidad) | Solo se sale de PENDING una vez | `TransactionTest` (4×4) y `Transaction.java` `transitionTo` | Verde | PASS |
| Transición única (BD, CR #7) | UPDATE con `WHERE status = PENDING`; 0 filas no pisa el resultado | `TransactionRepository.java:22-33`; `TransactionPersistenceIT.java:105-118` (1.ª = 1, 2.ª = 0, la fila sigue EXECUTED con `code` null); `TransactionServiceTest.java:313-325` (0 filas → `IllegalStateException` y log ERROR con `status=REJECTED` y `code=INSUFFICIENT_FUNDS`) | Verde contra Postgres real | PASS |
| AC4 / C3 | Sin `@Transactional` alrededor del proveedor | ArchUnit `ArchitectureTest.java:53-62` (transitiva) 9/9 | `@Transactional` del repositorio permitido: no depende del puerto (lo confirma ArchUnit en verde). La tx de `completePending` empieza después de la respuesta del proveedor. | PASS |
| Llamador indirecto | Alcance o justificación | `ArchitectureTest.java:112-133` | Fixture indirecto detectado; el servicio queda dentro del predicado | PASS |
| AC5 | Misma llave y mismo payload → existente, sin proveedor | `TransactionServiceTest.java:156-169` (`1500.0` contra `1500.00`) | `isSameAs`, `verifyNoInteractions(provider)`, sin `saveAndFlush` ni `completePending` | PASS |
| AC6 | Misma llave con otro payload → conflicto | `TransactionServiceTest.java:171-183` | `IDEMPOTENCY_KEY_CONFLICT` | PASS |
| AC7 | Carrera del UNIQUE → ganadora | `TransactionServiceTest.java:185-210`; `TransactionPersistenceIT` (UNIQUE real → DIVE y relectura) | La ganadora PENDING se devuelve sin proveedor y sin `completePending`. Si su payload difiere, conflicto. | PASS |
| Relectura en tx nueva | No usar una sesión rollback-only | `TransactionService.java:69-70` → `findByIdempotencyKey` del repo, con su propia tx porque no hay tx externa | Estructura e IT | PASS |
| AC8 | Unitarios del servicio con mock del puerto, y de la entidad | TransactionServiceTest 24, TransactionTest 26, ExecuteTransactionCommandTest 12 | Verde | PASS |
| C1 | Mapea V1 con `validate` | El contexto de los IT arranca | Verde | PASS |
| Escala del monto (FAIL v1 / CR #3) | Lo enviado al proveedor es igual a lo persistido; replay sin conflicto falso | `ExecuteTransactionCommand.java:25,32-38` (`setScale(2, UNNECESSARY)`); `ExecuteTransactionCommandTest.java:14-30` (1500, 1500.0, 1500.000 y 1.5E+3 dan 1500.00; 1500.005, 1.001 y 10000.001 lanzan IAE); `TransactionServiceTest.java:237-246` (1500.005: sin proveedor ni repo); `:248-258` (el proveedor recibe `1500.00` exacto, `equals`) | Verde | PASS |
| Columnas acotadas (FAIL v1 / CR #2) | Un dato largo o fuera de rango del proveedor no rompe el guardado | `Transaction.java` `canRecordExecution` (id ≤ 100, escala ≤ 2, ≤ 17 enteros), `code` recortado a 100 y `message` a 500; `TransactionServiceTest.java:272-295` (id de 101, balance de 18 enteros, 5500.005 → UNKNOWN `PROVIDER_INVALID_RESPONSE`, con `transactionId=` y `balance=` en `message`); `TransactionPersistenceIT.java:120-145` (code 150 y message 800 recortados en Postgres; id de 100 y `99999999999999999.99` intactos) | Verde | PASS |
| No perder el resultado (FAIL v1 / CR #1) | Si el UPDATE falla: log ERROR con el resultado y relanzar | `TransactionService.java:82-102`; `TransactionServiceTest.java:297-311` (la misma instancia de excepción; el log contiene `ERROR`, `falló el UPDATE`, `status=EXECUTED`, `providerTransactionId=txn-789`) | Verde | PASS |
| Llave en blanco (CR #6) | blank → null | `ExecuteTransactionCommand.java:26-28`; `ExecuteTransactionCommandTest.java:32-38` (null, "", espacios, tab); `TransactionServiceTest.java:260-270` (sin `findByIdempotencyKey`, `idempotencyKey()` null) | Verde | PASS |
| CR #4, #5, #8 aceptados | Justificación en el reporte | `impl_execution_service.md` § "Decisiones aceptadas" | #4: la llave identifica el intento (README, F7). #5: la ganadora PENDING no provoca segunda llamada; el código HTTP lo decide F5. #8: el GET usa el repo, ya transaccional; se revisa en F5. Razonables. | PASS |
| UNKNOWN y no FAILED en aprobación no registrable | B4 | `TransactionService.java:110-116` | Consistente: el proveedor sí aplicó la operación | PASS |

## Estándar Java L3 (filas que verifiqué yo)

| Regla | Evidencia | Resultado |
|-------|-----------|-----------|
| A5 | `TransactionService.java:104-121`: `switch` sobre sellado con `case Approved … when` antes del `case Approved` general, sin `default`; compila | PASS |
| B2 | `ExecuteTransactionCommand.java:32-38` normaliza la escala; `Transaction.java` `hasSamePayloadAs` usa `compareTo` | PASS |
| B3 | Sin setters; `mark*` package-private con `transitionTo`; transición única también en BD (`TransactionRepository.java:28`) | PASS |
| C3 | El servicio no tiene tx; `@Transactional` solo en `TransactionRepository.java:22` (método corto, posterior al proveedor); ArchUnit verde | PASS |
| F3 | `TransactionService.java:98-102`, `:111-112`: logs parametrizados sin monto, cuenta ni llave | PASS |
| H2 | `TransactionService.java:26-34`: constructor y campos `final` | PASS |
| H5 | El helper de test `verifyNoCompletion` (`TransactionServiceTest.java:327`) se usa en `:168` y `:197`; no hay código muerto | PASS |

## Checkpoints

- C1: [x] Archivos del arnés presentes; `init.sh` termina con exit 0.
- C2: [x] Solo la feature 4 está `in_progress` y `current.md` describe la sesión. La entrada de history toca en el cierre.
- C3: [x] Paquete `transaction/`, ArchUnit 9/9, sin dependencias nuevas ni prohibidas, sin `@Autowired` en campos.
- C4: [x] `BUILD SUCCESS` con 153 + 10 tests y ninguno `@Disabled`. Cada regla cubierta tiene un test que afirma el resultado concreto. Bordes 1.00, 10000.00, 10000.01, USD y escala 1500.005 cubiertos.
- C5: [x] Para el alcance de la feature. Por indicación del leader, `docs/sonar-decisiones.md` queda sin trackear y fuera del commit. Los reportes `impl_`, `review_` y `_codereview` se archivan al cerrar.

## Cambios requeridos

Ninguno.

## Notas (no bloqueantes, para la feature 5)

- Un monto con más de 2 decimales lanza `IllegalArgumentException` al construir `ExecuteTransactionCommand`. La feature 5 debe frenarlo antes con `@Digits(integer = 17, fraction = 2)` en el DTO (→ 400), y el `@RestControllerAdvice` no debe dejar escapar esa IAE como 500 con mensaje interno.
- La ganadora PENDING en un replay o una carrera, y el `IllegalStateException` cuando hay 0 filas, necesitan su código HTTP y su `ProblemDetail` en la feature 5.
- La prueba por ejecución de "sin tx activa durante la llamada al proveedor" y la carrera concurrente real con N solicitudes quedan para el IT de la feature 6.
