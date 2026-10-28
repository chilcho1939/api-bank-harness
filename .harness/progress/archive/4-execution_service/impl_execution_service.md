# Implementación — feature 4 `execution_service` (RN04, RN05, RN06)

Rama `feat/4-execution_service`. Sin commits. Feature sigue `in_progress`.

## Archivos

Creados (`src/main/java/mx/gilortiz/transactions/transaction/`):

- `Transaction.java`: entidad JPA que mapea V1 tal cual (`ddl-auto: validate`). Sin setters; transiciones `markExecuted/markRejected/markFailed/markUnknown`, solo válidas desde `PENDING`.
- `TransactionStatus.java`: `PENDING, EXECUTED, REJECTED, FAILED, UNKNOWN` (los mismos del CHECK de V1).
- `TransactionRepository.java`: `JpaRepository<Transaction, UUID>` + `findByIdempotencyKey`. Es package-private.
- `ExecuteTransactionCommand.java`: record con `accountId, type, amount, currency, description, idempotencyKey`. `description` e `idempotencyKey` son opcionales.
- `IdempotencyKeyConflictException.java`: código estable `IDEMPOTENCY_KEY_CONFLICT`. Feature 5 lo mapea a 409.
- `TransactionService.java`: el caso de uso. Package-private y sin `@Transactional`.

Modificados:

- `TransactionRules.java`: solo se agregó `@Component` (línea 7) para inyectarlo en el servicio.
- `src/test/.../architecture/ArchitectureTest.java`: la regla `@Transactional` ahora es transitiva y hay 2 tests nuevos.

Tests creados:

- `src/test/.../transaction/TransactionTest.java` (17)
- `src/test/.../transaction/TransactionServiceTest.java` (16)
- `src/test/.../transaction/TransactionPersistenceIT.java` (4, Testcontainers)
- `src/test/.../architecture/fixture/transaction/ProviderCallerFixture.java`
- `src/test/.../architecture/fixture/transaction/TransactionalIndirectCallerFixture.java`

Migraciones: **no hace falta V2**. Los cinco campos del payload ya son columnas de V1, así que la idempotencia compara contra ellos directamente, sin guardar un hash.

Dependencias nuevas: ninguna. `@UuidGenerator(style = VERSION_7)` viene con Hibernate 7.4, que ya está en el starter de JPA.

## Verificación

`./.harness/init.sh` → `EXIT=0`. Salida: `[OK] verify verde (unitarios + integración + ArchUnit)` y `[OK] Entorno listo.`

| Suite | Tests | Fallos | Errores | Omitidos |
|---|---|---|---|---|
| Surefire (`*Test`) | 124 (de ellos, 35 nuevos) | 0 | 0 | 0 |
| Failsafe (`*IT`) | 7 (de ellos, 4 nuevos) | 0 | 0 | 0 |

Detalle de los nuevos: TransactionTest 17, TransactionServiceTest 16, ArchitectureTest +2 (ahora 9) y TransactionPersistenceIT 4.

En la salida del log aparecen dos líneas esperadas:
- El `ERROR` de `HttpTransactionProvider`, que viene de un test de feature 3 sobre "excepción inesperada".
- El `WARN duplicate key ... uk_transactions_idempotency_key`, que es justo lo que provoca el IT a propósito.

## Decisiones

### Límites de transacción (C2, C3)

- El servicio no lleva `@Transactional`. Cada paso de BD usa la transacción corta que ya trae `SimpleJpaRepository`:
  - `saveAndFlush`: hace el INSERT `PENDING` y el commit. Hace flush para que la violación del UNIQUE salte ahí mismo como `DataIntegrityViolationException`.
  - Llamada al proveedor: sin ninguna transacción abierta. `open-in-view: false` y no hay contexto de persistencia vivo.
  - `save`: es un `merge` de la entidad desconectada, es decir SELECT + UPDATE en su propia transacción.
- Descarté `TransactionTemplate` y un componente `@Transactional` aparte: no agregaban nada, porque cada bloque es una sola operación del repositorio.
- El comentario de clase de `TransactionService.java:12-13` explica el porqué.

### Transiciones (B3, B4)

- `Transaction.transitionTo` (`Transaction.java:138`) lanza `IllegalStateException` si el estado no es `PENDING`. Así cada transacción cambia de estado una sola vez.
- `ProviderResult` se mapea con un `switch` exhaustivo sobre el tipo sellado (`TransactionService.java:77-82`), sin `default`.
- Cómo queda cada resultado:
  - `Approved` → `EXECUTED` con `providerTransactionId` = `transactionId` y `balanceAfter` = `balance` (RN05).
  - `Rejected`, `Failed` y `Unknown` → su estado respectivo, guardando `code` y `message` (RN06). En `FAILED` y `UNKNOWN` el código es el técnico del adaptador.
- `executedAt` del proveedor no se persiste: no tiene columna en V1 y la spec no lo pide en el response.
- `message` se recorta a 500 caracteres, el tamaño de la columna (`Transaction.java:147`). Ese texto lo escribe un tercero; si viniera más largo, el UPDATE fallaría y la transacción se quedaría `PENDING` aunque el proveedor sí respondió.
- `code` y `providerTransactionId` no se recortan: son identificadores y recortarlos los corrompería.
- `createdAt` se trunca a microsegundos, la precisión de `timestamptz`, para que el valor de la respuesta sea igual al que se relee de la BD.

### Idempotencia (C4, C5)

- El orden sigue el diagrama de `docs/architecture.md`: reglas → búsqueda por llave → INSERT. Una llave repetida con payload que no cumple las reglas recibe el 422 de la regla.
- Llave existente:
  - Con el mismo payload, se devuelve la transacción guardada tal como está, sin llamar al proveedor.
  - Con un payload distinto, se lanza `IdempotencyKeyConflictException`.
- **El payload comparado son 5 campos** (`Transaction.java:118`): `accountId`, `type`, `amount` (con `compareTo`, así que `1500.0` es igual a `1500.00`), `currency` y `description`. La nota del líder listaba 4 campos; agregué `description` porque es parte del request de la spec y una repetición con otra descripción no es la misma solicitud. Si se prefiere comparar solo 4, es quitar una línea.
- Carrera: dos solicitudes con la misma llave pasan la búsqueda a la vez y la BD decide.
  - El perdedor recibe `DataIntegrityViolationException` en `saveAndFlush`, relee la ganadora por llave y le aplica la misma comparación de payload (`TransactionService.java:63-66`). Postgres bloquea la segunda inserción hasta que la primera hace commit, así que la ganadora ya es visible al releer.
  - Si no hay llave o no aparece ganadora (la violación fue de otra restricción), se relanza la excepción original. No se oculta.
- Si la ganadora sigue `PENDING` (su llamada al proveedor no ha terminado), se devuelve así. La spec no define esto; lo dejo anotado para el README de feature 7.
- Pendiente para feature 5: Bean Validation debe limitar `amount` a 2 decimales (`@Digits(fraction = 2)`). Si no, Postgres redondea un `1500.005` al guardarlo y una repetición con el mismo valor daría conflicto.

### Regla ArchUnit para llamadores indirectos (pendiente de feature 3)

- `DEPEND_ON_PROVIDER_PORT` ahora usa `getTransitiveDependenciesFromSelf()` (`ArchitectureTest.java:53-58`). Cubre el servicio, y también al futuro controller o a cualquier facade que llegue al puerto por la cadena de dependencias. Conserva la detección de meta-anotaciones.
- Alcance: el cierre transitivo solo recorre clases importadas (las de producción), así que no explota hacia las librerías.
- Se agregaron dos tests:
  - Un fixture indirecto (`@Transactional` sobre una clase que solo conoce a un llamador directo limpio) que la regla detecta (`ArchitectureTest.java:114`).
  - Una comprobación de que el predicado sí alcanza a `TransactionService` en producción (`ArchitectureTest.java:129`), para que la regla no pase sin revisar nada.
- Límite conocido: en un futuro `TransactionQueryService` del GET, `@Transactional(readOnly = true)` está permitido mientras no dependa, directa o transitivamente, del servicio de ejecución.

### Visibilidad

- Package-private: servicio, repositorio y métodos `mark*`, `pending` y `hasSamePayloadAs`.
- Públicos: `Transaction` (sus accessors), `TransactionStatus`, `ExecuteTransactionCommand` e `IdempotencyKeyConflictException`. Los van a usar los DTOs de `transaction/dto` y el handler de `common/web` en feature 5.

## Auditoría Java L3

| Regla | Estado | Evidencia (`archivo:línea`) | Nota |
|-------|--------|------------------------------|------|
| A1 | ✅ | `transaction/Transaction.java:22`, `transaction/TransactionService.java:17` | Todo vive en `transaction/` |
| A2 | ✅ | `TransactionService.java:35-52` → `TransactionRepository.java:7` | El controller llega en feature 5 |
| A3 | ✅ | `TransactionService.java:50` (usa `TransactionProvider`) | No hay ninguna referencia a `provider.http` |
| A4 | ✅ | `ArchitectureTest.java` `domainDoesNotDependOnProviderHttp` | Verde |
| A5 | ✅ | `TransactionService.java:77-82` | `switch` exhaustivo sobre el tipo sellado, sin `default` |
| A6 | ✅ | `TransactionService.java:17` | Clase concreta, sin interfaz |
| A7 | N/A | — | Los DTOs son de feature 5. La entidad no se expone (todavía no hay API) |
| B1 | ✅ | `TransactionService.java:36` | Reutiliza `TransactionRules` de feature 2 |
| B2 | ✅ | `Transaction.java:121` | `compareTo` en la comparación de payload |
| B3 | ✅ | `Transaction.java:91-111`, `:138-143` | Sin setters; una sola transición |
| B4 | ✅ | `TransactionStatus.java`, `Transaction.java:110` | `UNKNOWN` es distinto de `FAILED` |
| B5 | ✅ | `IdempotencyKeyConflictException.java` (`CODE`) | Código en inglés y mensaje en español |
| C1 | ✅ | `TransactionPersistenceIT` (contexto con `validate` contra V1) | Sin V2 |
| C2 | ✅ | `TransactionService.java:45` antes de `:50` | `TransactionServiceTest.java:76` |
| C3 | ✅ | `TransactionService.java:12-17` (sin `@Transactional`), `ArchitectureTest.java:53-62` | La regla ahora es transitiva |
| C4 | ✅ | `TransactionService.java:44-47`, `:63-66` | UNIQUE de BD + captura de la violación + relectura |
| C5 | ✅ | `TransactionService.java:70-71` | Conflicto con excepción; feature 5 la mapea a 409 |
| C6 | N/A | — | Consulta paginada: feature 5 (índices ya en V1) |
| C7 | ✅ | `Transaction.java:27-28` | `@UuidGenerator(VERSION_7)` en la app; el IT afirma `version() == 7` |
| D1–D5 | N/A | — | Adaptador de feature 3, sin cambios |
| E1–E7 | N/A | — | API: feature 5 |
| F1–F2 | N/A | — | Sin cambios de configuración |
| F3 | ✅ | `TransactionService.java:65` | SLF4J parametrizado, solo el id (no se loguea la llave ni el payload) |
| G1 | ✅ | `TransactionTest.java`, `TransactionServiceTest.java` | Mock del puerto y del repositorio; entidad real |
| G2 | N/A | — | Feature 3 |
| G3 | N/A | — | Feature 5 |
| G4 | ✅ parcial | `TransactionPersistenceIT.java` | Mapeo + UNIQUE. El E2E y la carrera concurrente real son de feature 6 |
| G5 | ✅ | `ArchitectureTest.java:83`, `:114`, `:129` | |
| G6 | ✅ | `TransactionServiceTest.java:51-59` (1.00, 10000.01, USD), `:119` (DEBIT 10000.00 aceptado) | |
| G7 | ✅ | `@DisplayName` en español en todos los tests nuevos | Cada test afirma estado, datos o número de llamadas |
| H1 | ✅ | records, `switch` con patrones | |
| H2 | ✅ | `TransactionService.java:25-29` | Inyección por constructor, campos `final` |
| H3 | ✅ | — | Sin Lombok |
| H4 | ✅ | `TransactionService.java:12-13`, `:62`, `Transaction.java:26`, `:86`, `:146` | Los comentarios explican el porqué |
| H5 | ✅ | — | Sin `System.out` ni TODOs |

## Pre-matriz de reglas

| Regla | Qué exige | Implementación | Test que la afirma | Verificación manual |
|-------|-----------|----------------|--------------------|---------------------|
| RN04 | Si falla RN01 (`1.00`), el proveedor no se llama | `TransactionService.java:36` antes de `:50` | `TransactionServiceTest.java:65` (caso AMOUNT_TOO_LOW, `verifyNoInteractions(provider)`) | BLOCKED: no hay endpoint (feature 5) |
| RN04 | Si falla RN02 (DEBIT `10000.01`), el proveedor no se llama | ídem | `TransactionServiceTest.java:65` (DEBIT_AMOUNT_EXCEEDS_LIMIT) | BLOCKED (feature 5) |
| RN04 | Si falla RN03 (`USD`), el proveedor no se llama | ídem | `TransactionServiceTest.java:65` (UNSUPPORTED_CURRENCY) | BLOCKED (feature 5) |
| RN04 | Una regla fallida no persiste nada, ni siquiera con llave | ídem | `TransactionServiceTest.java:65` (`verifyNoInteractions(repository)`, caso con `KEY`) | BLOCKED (feature 5) |
| RN04/C2 | `PENDING` se guarda antes de llamar al proveedor | `TransactionService.java:45`, `:50` | `TransactionServiceTest.java:76` (InOrder + estado `PENDING` al insertar y al llamar) | BLOCKED (feature 5) |
| RN05 | Aprobado → `EXECUTED` + `providerTransactionId` | `Transaction.java:91-96`, `TransactionService.java:78-79` | `TransactionServiceTest.java:95`, `TransactionTest.java:43` | BLOCKED (feature 5) |
| RN05 | Aprobado → `balanceAfter` = `balance` del proveedor | ídem | `TransactionServiceTest.java:95`, `TransactionPersistenceIT.java:43` (persistido y releído) | BLOCKED (feature 5) |
| RN06 | Rechazo 4xx/5xx → `REJECTED` con `code` | `Transaction.java:100`, `TransactionService.java:80` | `TransactionServiceTest.java:119` [REJECTED], `TransactionPersistenceIT.java:69` | BLOCKED (feature 5) |
| RN06 | Rechazo → `message` persistido | `Transaction.java:131-136` | `TransactionServiceTest.java:119`, `TransactionPersistenceIT.java:69`, `TransactionTest.java:103` (recorte a 500) | BLOCKED (feature 5) |
| Estados | `FAILED` / `UNKNOWN` con sus datos | `Transaction.java:105`, `:110`; `TransactionService.java:81-82` | `TransactionServiceTest.java:119` [FAILED], [UNKNOWN] | BLOCKED (feature 5) |
| Transición única | Un estado final no cambia otra vez | `Transaction.java:138-143` | `TransactionTest.java:90` (4×4 combinaciones) | — |
| Idempotencia | Misma llave + mismo payload → la existente, sin proveedor | `TransactionService.java:38-41`, `:69-73` | `TransactionServiceTest.java:151` | BLOCKED (feature 5) |
| Idempotencia | Misma llave + payload distinto → conflicto | `TransactionService.java:70-71` | `TransactionServiceTest.java:166`, `TransactionTest.java:140` (5 campos) | BLOCKED (feature 5) |
| Idempotencia | Violación del UNIQUE en carrera → la ganadora | `TransactionService.java:46-47`, `:63-66` | `TransactionServiceTest.java:180`, `:195` (ganadora con otro payload → conflicto); `TransactionPersistenceIT.java:85` (el UNIQUE real lanza `DataIntegrityViolationException`) | BLOCKED (feature 5 / carrera real en 6) |
| C3 | Sin `@Transactional` alrededor del proveedor, directo o indirecto | `TransactionService.java:17`, `ArchitectureTest.java:53-62` | `ArchitectureTest.java:83`, `:89`, `:101`, `:114`, `:129` | — |

## Commits sugeridos

```
feat(transaction): agrega entidad Transaction con estados y transiciones únicas
feat(transaction): agrega servicio de ejecución con PENDING previo e idempotencia por llave (RN04–RN06)
test(architecture): prohíbe @Transactional en llamadores indirectos del puerto del proveedor
```

O uno solo: `feat(transaction): agrega servicio de ejecución, entidad e idempotencia (RN04–RN06)`.

---

# Vuelta 2

Fuentes: `review_execution_service.md` (§ Cambios requeridos, ítems 1 y 2) y `review_execution_service_codereview.md` (corregir #1, #2, #3, #6 y #7). El ítem 3 del reviewer (`docs/sonar-decisiones.md`) le corresponde al leader; no lo toqué y no entra en el commit.

**Esta sección reemplaza** lo que en la vuelta 1 se decía sobre `repository.save` / `merge`: el UPDATE final ahora es dirigido.

## Verificación

`./.harness/init.sh` → `EXIT=0`, con `[OK] verify verde (unitarios + integración + ArchUnit)` y `[OK] Entorno listo.`

| Suite | Tests | Fallos | Errores | Omitidos |
|---|---|---|---|---|
| Surefire | 153 (vuelta 1: 124) | 0 | 0 | 0 |
| Failsafe | 10 (vuelta 1: 7) | 0 | 0 | 0 |

Detalle por clase de test:

| Clase | Vuelta 1 | Vuelta 2 |
|---|---|---|
| ExecuteTransactionCommandTest | — (nueva) | 12 |
| TransactionTest | 17 | 26 |
| TransactionServiceTest | 16 | 24 |
| TransactionPersistenceIT | 4 | 7 |

En el log aparecen dos líneas `ERROR ... TransactionService -- Resultado del proveedor sin guardar...`. Son esperadas: las emiten a propósito los tests de 2b y #7 y las capturan con `OutputCaptureExtension`.

## Cambios por ítem

| Ítem | Cambio | Implementación | Tests |
|---|---|---|---|
| Reviewer 1 / CR #3: escala del monto | El comando normaliza `amount` a escala 2 con `RoundingMode.UNNECESSARY`. Con más de 2 decimales significativos lanza `IllegalArgumentException("El monto admite como máximo 2 decimales")`, antes de reglas, BD y proveedor. Así lo que recibe el proveedor es idéntico a lo que guarda `NUMERIC(19,2)`. | `ExecuteTransactionCommand.java:25`, `:32-38` | `ExecuteTransactionCommandTest.java:17` (`1500`, `1500.0`, `1500.000` y `1.5E+3` dan `1500.00`), `:26` (`1500.005`, `1.001`, `10000.001` se rechazan); `TransactionServiceTest.java:239` (`1500.005`: `verifyNoInteractions(provider)` y `verifyNoInteractions(repository)`), `:250` (el proveedor recibe `1500.00` cuando llega `1500`); el replay `1500.0` contra `1500.00` sigue en `:158` |
| Reviewer 2a / CR #2: columnas acotadas | (i) `code` se recorta a 100 caracteres, igual que `message` a 500 (`Transaction.java:152-153`). (ii) `Transaction.canRecordExecution` (`:98`) comprueba que el id mida 100 o menos y que el balance tenga escala 2 o menos y 17 o menos dígitos enteros. (iii) Una `Approved` que no cabe pasa a `UNKNOWN` con code `PROVIDER_INVALID_RESPONSE`, y el `message` lleva `transactionId=` y `balance=` para conciliar (`TransactionService.java:105-116`, con un `case` guardado por `when`; el `switch` sigue siendo exhaustivo). (iv) `markExecuted` rechaza datos que no caben (`Transaction.java:109`), como invariante de respaldo. | ídem | `TransactionTest.java:113` (code recortado), `:135` (bordes: id de 100 y de 101, `99999999999999999.99` y `100000000000000000.00`, `5500.005`), `:141`; `TransactionServiceTest.java:282` (3 escenarios terminan en UNKNOWN); `TransactionPersistenceIT.java:123` (code de 150 y message de 800 se guardan recortados sin error en Postgres), `:137` (id de 100 y balance en el límite se guardan intactos) |
| Reviewer 2b / CR #1: no perder el resultado si falla el UPDATE | `recordOutcome` captura cualquier `RuntimeException` del UPDATE, registra un log `ERROR` con el motivo, el id, `status`, `providerTransactionId` y `code` (sin payload, monto ni llave) y relanza la excepción. La fila queda `PENDING` para conciliar. | `TransactionService.java:82-102` | `TransactionServiceTest.java:300` (la excepción es la misma instancia; el log contiene `ERROR`, `falló el UPDATE`, `status=EXECUTED`, `providerTransactionId=txn-789`) |
| CR #6: llave en blanco | `""`, espacios o tabulador se convierten en `null` en el comando, así que no se busca ni se guarda llave. | `ExecuteTransactionCommand.java:26-28` | `ExecuteTransactionCommandTest.java:36`; `TransactionServiceTest.java:262` (no se llama a `findByIdempotencyKey`) |
| CR #7: UPDATE dirigido | `TransactionRepository.completePending`: `@Modifying` JPQL `UPDATE ... WHERE t.id = :id AND t.status = PENDING`. Corre en su propia transacción corta (`@Transactional` en el método del repositorio, que no depende del puerto: la regla ArchUnit sigue verde). Si actualiza 0 filas, el servicio lo trata como conflicto de estado: log `ERROR` con el resultado y `IllegalStateException("... no existe o ya no está PENDING")`. Ya no hay SELECT previo (`merge`), y la BD también garantiza la transición única. | `TransactionRepository.java:22-33`, `TransactionService.java:92-95` | `TransactionPersistenceIT.java:107` (primer UPDATE = 1; segundo = 0 y la fila sigue EXECUTED sin `code`); `TransactionServiceTest.java:316` (0 filas → `IllegalStateException` + log con `status=REJECTED`, `code=INSUFFICIENT_FUNDS`); orden INSERT → proveedor → UPDATE en `:82` |

### Decisiones nuevas

- **Escala en el comando y no en `TransactionRules`.** La escala es un invariante de formato del dinero (la columna es `NUMERIC(19,2)`), no una regla RN de la spec. Por eso no inventé un código de negocio nuevo. En feature 5, `@Digits(integer = 17, fraction = 2)` en el DTO la convierte en un 400. La excepción del comando es la última barrera y no debería alcanzarse desde HTTP.
- **Aprobación que no cabe → `UNKNOWN`, no `FAILED`.** El proveedor sí aplicó la operación, pero no podemos registrar su resultado tal cual. Eso es un resultado incierto que hay que conciliar (B4). El id y el saldo del proveedor quedan en `message`.
- **Por qué recortar `code` pero no `providerTransactionId`.** `code` es un texto del proveedor y basta con su prefijo. `providerTransactionId` es una llave de conciliación: recortarla la corrompería. Por eso, si no cabe, el resultado se trata como respuesta inválida.

## Decisiones aceptadas (sin cambio de código)

| CR | Decisión | Razón |
|---|---|---|
| #4: un replay devuelve FAILED y la llave queda "quemada" | Se acepta. | Es la semántica estándar de `Idempotency-Key`: la llave identifica un intento, no una intención. Un replay devuelve fielmente el resultado de ese intento. Para reintentar, el cliente usa una llave nueva. Además `FAILED` y `UNKNOWN` no se pueden distinguir de forma segura sin conciliar, y reintentar con la misma llave tras un `UNKNOWN` podría duplicar dinero. Va en el README (feature 7). |
| #5: un duplicado concurrente devuelve la ganadora PENDING | Se acepta. | No hay segunda llamada al proveedor, que es la garantía crítica. La feature 5 decide el código HTTP para un replay `PENDING` en curso (p. ej. 409 o 202) y lo documenta. La carrera real con N solicitudes se prueba en el IT de la feature 6. |
| #8: la regla ArchUnit transitiva rompería un GET `readOnly` futuro | Se acepta por ahora. | La regla solo alcanza a clases que dependen directa o transitivamente del puerto. Un GET que use el repositorio no queda incluido, y el repositorio ya es transaccional (`readOnly` heredado de `SimpleJpaRepository`), así que el controller no necesita `@Transactional`. Se revisa en la feature 5 si aparece la necesidad. |

## Auditoría Java L3: filas que cambian

| Regla | Estado | Evidencia | Nota |
|-------|--------|-----------|------|
| A5 | ✅ | `TransactionService.java:105-122` | Exhaustivo; el `case Approved ... when` va antes del `case Approved` general |
| B2 | ✅ | `ExecuteTransactionCommand.java:32-38`, `Transaction.java:98-103` | Escala normalizada; `compareTo` se mantiene |
| B3 | ✅ | `Transaction.java:109`, `TransactionRepository.java:28` | Transición única también en la BD (`WHERE status = PENDING`) |
| B4 | ✅ | `TransactionService.java:111-116` | Una aprobación que no se puede registrar pasa a `UNKNOWN` |
| C2 | ✅ | `TransactionService.java:82-102` | El resultado no se pierde: queda en el log ERROR y la fila PENDING se puede conciliar |
| C3 | ✅ | `TransactionRepository.java:22` | `@Transactional` solo en el método corto del repositorio, que no depende del puerto; ArchUnit 9/9 |
| F3 | ✅ | `TransactionService.java:98-102`, `:111-112` | Parametrizado; sin monto, cuenta ni llave |
| G1/G4/G6/G7 | ✅ | tests citados arriba | Bordes de escala y de columnas explícitos |

## Commits sugeridos (actualizados)

```
feat(transaction): agrega entidad Transaction con estados y transiciones únicas
feat(transaction): agrega servicio de ejecución con PENDING previo e idempotencia por llave (RN04–RN06)
test(architecture): prohíbe @Transactional en llamadores indirectos del puerto del proveedor
```

Los cambios de la vuelta 2 entran en el segundo commit. Si se prefiere un commit aparte: `fix(transaction): normaliza la escala del monto y protege el guardado del resultado del proveedor`.
