# Implementación — feature 5 `api_endpoints` (RN01, RN02, RN03, RN07)

Rama `feat/5-api_endpoints`. Sin commits (los sugeridos van al final).

## Archivos

### Creados (main)
- `src/main/java/mx/gilortiz/transactions/transaction/TransactionController.java` — `POST`/`GET /transactions`, sin `@Transactional`.
- `src/main/java/mx/gilortiz/transactions/transaction/TransactionExecution.java` — resultado de `execute`: `(transaction, created)`.
- `src/main/java/mx/gilortiz/transactions/transaction/TransactionSearchCriteria.java` — filtros RN07 → `Specification` (`allOf`).
- `src/main/java/mx/gilortiz/transactions/transaction/dto/CreateTransactionRequest.java` — record con Bean Validation.
- `src/main/java/mx/gilortiz/transactions/transaction/dto/TransactionResponse.java` — los 10 campos literales del response de la spec.
- `src/main/java/mx/gilortiz/transactions/common/web/ApiExceptionHandler.java` — único `@RestControllerAdvice` (extiende `ResponseEntityExceptionHandler`), RFC 9457 con `code`.
- `src/main/java/mx/gilortiz/transactions/common/web/InvalidField.java` — elemento de `errors` (`field`, `message`).
- `src/main/java/mx/gilortiz/transactions/common/web/PageResponse.java` — `content`, `page`, `limit`, `totalElements`, `totalPages`.

### Modificados (main)
- `transaction/TransactionService.java` — `execute` devuelve `TransactionExecution` (created/replay); nuevo `search(criteria, page, limit)` con `STABLE_ORDER` (`createdAt desc, id desc`).
- `transaction/TransactionRepository.java` — extiende `JpaSpecificationExecutor<Transaction>`.

### Tests
- Nuevo `transaction/TransactionControllerTest.java` (`@WebMvcTest`, 37 casos) — servicio y reglas **reales**; solo `TransactionProvider` y `TransactionRepository` son `@MockitoBean`.
- Nuevo `transaction/TransactionSearchIT.java` (7) — filtros, AND, paginación estable con `createdAt` empatado, contra Postgres (Testcontainers).
- Nuevo `OpenApiIT.java` (2) — `/v3/api-docs` y `/swagger-ui.html` → `/swagger-ui/index.html` 200.
- `transaction/TransactionServiceTest.java` — adaptado a `TransactionExecution` (afirma `created()` true/false; la ganadora de la carrera llega `PENDING`) + test de `search` (página, tamaño, orden).
- `architecture/ArchitectureTest.java` — `transactionalRuleCoversController`: la regla transitiva alcanza a `TransactionController`.

## Dependencias
Ninguna nueva (springdoc, validation y Spring Data ya estaban en el pom). Sin ADR.

## Resultado de verificación
`./.harness/init.sh` → exit 0 (`[OK] verify verde (unitarios + integración + ArchUnit)`).

| Suite | Tests |
|---|---|
| Surefire (`*Test`) | 192 (ProviderProperties 18, Architecture 10, HttpTransactionProvider 30, ProviderClientConfiguration 4, TransactionService 25, ExecuteTransactionCommand 12, **TransactionController 37**, TransactionRules 30, Transaction 26) |
| Failsafe (`*IT`) | 19 (ApplicationContext 3, **OpenApi 2**, TransactionPersistence 7, **TransactionSearch 7**) |
| Total | **211**, 0 fallos, 0 errores, 0 skipped |

## Tabla excepción → HTTP → `code`

Todos los errores son `application/problem+json` (RFC 9457: `type`, `title`, `status`, `detail`, `instance`) + propiedad `code`. Todo 400 trae además `errors: [{field, message}]` (posiblemente vacía).

| Origen | HTTP | `code` | `errors` / `detail` |
|---|---|---|---|
| `@Valid` del body (`MethodArgumentNotValidException`) o validación de método (`HandlerMethodValidationException`: body, header `Idempotency-Key`, `page`, `limit`) | 400 | `VALIDATION_ERROR` | un elemento por campo; header/param con su nombre HTTP (`Idempotency-Key`, `limit`), ordenado por campo |
| Query param con tipo inválido (`status=DONE`, `limit=diez`) — `MethodArgumentTypeMismatchException` | 400 | `VALIDATION_ERROR` | `[{field: "<param>", message: "tiene un valor inválido"}]` |
| Body ilegible (`HttpMessageNotReadableException`): JSON roto o enum desconocido (`type: "TRANSFER"`) | 400 | `MALFORMED_REQUEST` | campo desde la ruta de Jackson si existe; si no, `[]`. Nunca se expone el mensaje de Jackson |
| `BusinessRuleException` (RN01–RN03) | 422 | `AMOUNT_TOO_LOW` / `DEBIT_AMOUNT_EXCEEDS_LIMIT` / `UNSUPPORTED_CURRENCY` | `detail` = mensaje en español de la regla |
| `IdempotencyKeyConflictException` | 409 | `IDEMPOTENCY_KEY_CONFLICT` | `detail` = "La Idempotency-Key ya se usó con un payload distinto" |
| `IllegalStateException` (UPDATE final con 0 filas), `DataAccessException`, `IllegalArgumentException`, cualquier otra | 500 | `INTERNAL_ERROR` | `detail` = "Ocurrió un error interno"; stack y mensaje solo al log (`ERROR`, método + URI) |
| Otros errores de Spring MVC (405, 406, 415, 404…) | su estado | nombre del `HttpStatus` (`METHOD_NOT_ALLOWED`…) | — |

## Forma de las respuestas

`POST /transactions` — 201 (creada, incluso si el proveedor la rechaza: E4) o 200 (replay):
```json
{"id":"…","accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Transferencia recibida","status":"EXECUTED","providerTransactionId":"txn-789","balanceAfter":5500.00,"createdAt":"2026-10-28T20:47:50.423330Z"}
```
Exactamente los 10 campos del response de la spec (afirmado por conjunto de claves en `TransactionControllerTest.java:91`). Campos sin valor se serializan `null`.

`GET /transactions?accountId&status&type&page&limit` — 200 (la spec no define la forma; se usa la de E5):
```json
{"content":[<TransactionResponse>…],"page":0,"limit":20,"totalElements":1,"totalPages":1}
```
- `page`: base 0, default 0, rango `0..21474836` (`Integer.MAX_VALUE / 100`, evita desbordar el offset).
- `limit`: default 20, rango `1..100`; fuera de rango → 400.
- Filtros opcionales combinados con AND; `accountId` en blanco = sin filtro; `status`/`type` con valor fuera del enum → 400.
- Orden estable `createdAt desc, id desc` (usa los índices `(x, created_at, id)` de V1 en recorrido inverso).

## Decisiones no obvias

1. **200 replay vs 201 creada.** `TransactionService.execute` devuelve `TransactionExecution(transaction, created)`. 201 solo cuando esta solicitud insertó la fila; repetición con la misma llave (incluida la que pierde la carrera del UNIQUE) → 200. Permite al cliente distinguir "lo creé" de "ya existía" sin cambiar el cuerpo.
2. **Duplicado en vuelo → 200 con `status: PENDING`.** Si la original aún espera al proveedor, el replay devuelve la fila tal cual (PENDING) sin bloquear ni llamar al proveedor. No hay `GET /transactions/{id}` en la spec; el cliente puede consultar con `GET /transactions?accountId=…` o reintentar con la misma llave. Probado en `TransactionControllerTest.java:159`.
3. **`amount` sin `@Positive` y `currency` sin `@Size`.** Monto ≤ 1.00 (incluidos 0 y negativos) es RN01 → 422 `AMOUNT_TOO_LOW`; cualquier moneda ≠ `MXN` (incluida `USDX` o `mxn`) es RN03 → 422. Bean Validation solo cubre formato: obligatoriedad, `@Digits(integer = 17, fraction = 2)` (cabe en `NUMERIC(19,2)`), longitudes de columna (`accountId` ≤ 100, `description` ≤ 255). Así el `IllegalArgumentException` de `ExecuteTransactionCommand` ya no es alcanzable desde HTTP (y si lo fuera, sale como 500 genérico).
4. **`IllegalStateException` de 0 filas → 500 genérico**, no 409: no es un error del cliente, es un estado inesperado del servidor que requiere conciliación (ya queda en el log del servicio). El body no lleva el id ni "PENDING" (afirmado en `TransactionControllerTest.java:328`).
5. **`code` en todo ProblemDetail**: los errores propios de Spring pasan por `handleExceptionInternal`, que agrega `code` (nombre del estado) y `errors: []` a los 400 que no lo traigan.
6. **Consulta sin `@Transactional`**: `findAll(Specification, Pageable)` ya corre en la transacción de solo lectura de `SimpleJpaRepository`; la entidad no tiene asociaciones perezosas, así que el mapeo a DTO fuera de la transacción es seguro. El conteo y la página son dos lecturas independientes (aceptable para un listado). Vive en `TransactionService` (sin clase extra): el controller sigue sin `@Transactional`, verificado por ArchUnit.
7. **`@WebMvcTest` con servicio y reglas reales** (`@Import`): los 422 de RN01–RN03 se prueban con las reglas de verdad en la capa HTTP, no con un mock que lance la excepción.
8. **Paginación por offset**: con millones de filas, páginas profundas son caras; keyset (`createdAt`,`id`) queda como mejora documentable en el README (feature 7).

### Señalado para el leader (fidelidad a la spec)
- El response de la spec **no** incluye `code`/`message`; por literalidad no se exponen. Consecuencia: un `REJECTED` no le dice al cliente el motivo (queda persistido, RN06). Si se quiere exponerlo, requiere decisión explícita/ADR (feature 7).

## Smoke HTTP (nivel 3)

Los puertos 5432/8089 los ocupan contenedores de otro proyecto; se levantó un entorno aislado (Postgres :55432, WireMock :58089 con stub APPROVED, app jar :58080) y se desmontó al terminar.

| Caso | Comando (resumen) | HTTP | Cuerpo relevante |
|---|---|---|---|
| Creada | `POST` CREDIT 1500.00 MXN | 201 | 10 campos, `status: EXECUTED`, `providerTransactionId: txn-789`, `balanceAfter: 5500.00` |
| RN01 borde | `amount: 1.00` | 422 | `code: AMOUNT_TOO_LOW` |
| RN01 borde | DEBIT `1.01` | 201 | `EXECUTED` |
| RN02 borde | DEBIT `10000.00` | 201 | `EXECUTED` |
| RN02 borde | DEBIT `10000.01` | 422 | `code: DEBIT_AMOUNT_EXCEEDS_LIMIT` |
| RN02 CREDIT | CREDIT `10000.01` | 201 | `EXECUTED` |
| RN03 | `currency: USD` | 422 | `code: UNSUPPORTED_CURRENCY` |
| Formato | `amount: 1500.005` | 400 | `VALIDATION_ERROR`, `errors:[{field: amount}]` |
| Formato | `{}` | 400 | `errors` en accountId, amount, currency, type |
| Header | `Idempotency-Key` de 101 | 400 | `errors:[{field: Idempotency-Key}]` |
| Idempotencia | `key-1` primera / replay / otro monto | 201 / 200 / 409 | mismo `id` en replay; `IDEMPOTENCY_KEY_CONFLICT` |
| RN07 | `GET ?accountId=acc-777&status=EXECUTED&type=CREDIT&page=0&limit=10` | 200 | 1 elemento, `totalElements: 1` |
| RN07 | `GET ?page=1&limit=2` | 200 | 2 elementos, `totalElements: 5`, `totalPages: 3`, orden desc |
| RN07 | `GET ?limit=101` / `?status=DONE` | 400 / 400 | `field: limit` / `field: status` |
| OpenAPI | `GET /v3/api-docs` / `/swagger-ui.html` | 200 / 302 → `/swagger-ui/index.html` | |
| RN04 | `GET :58089/__admin/requests` | — | 5 llamadas = solo las 5 creadas; ni 422 ni replay llamaron al proveedor |

## Pre-matriz de reglas

| Regla | Qué exige | Implementación (`archivo:línea`) | Test que la afirma (`archivo:línea`) | Verificación manual (`curl`) |
|---|---|---|---|---|
| RN01 | `amount > 1.00` → 422 en HTTP | `TransactionRules.java:22`, `ApiExceptionHandler.java:60` | `TransactionControllerTest.java:180` (CREDIT/DEBIT 1.00, -5.00) | `amount 1.00` → 422 `AMOUNT_TOO_LOW` |
| RN01-borde | `1.00` exacto se rechaza | `TransactionRules.java:22` (`<= 0`) | `TransactionControllerTest.java:180` | ✅ 422 |
| RN01-borde | `1.01` se acepta | `TransactionRules.java:22` | `TransactionControllerTest.java:201` (CREDIT/DEBIT 1.01) | DEBIT 1.01 → 201 |
| RN01-tipos | aplica a CREDIT y DEBIT | `TransactionRules.java:22` | `TransactionControllerTest.java:180` (filas CREDIT y DEBIT 1.00) | — |
| RN02 | DEBIT `≤ 10,000.00` | `TransactionRules.java:25` | `TransactionControllerTest.java:180` (DEBIT 10000.01 → 422) | DEBIT 10000.01 → 422 `DEBIT_AMOUNT_EXCEEDS_LIMIT` |
| RN02-borde | `10,000.00` exacto se acepta | `TransactionRules.java:25` (`> 0`) | `TransactionControllerTest.java:201` (DEBIT 10000.00) | DEBIT 10000.00 → 201 |
| RN02-CREDIT | CREDIT sin límite | `TransactionRules.java:25` (`type == DEBIT`) | `TransactionControllerTest.java:201` (CREDIT 10000.01, 999999999.99) | CREDIT 10000.01 → 201 |
| RN03 | solo `MXN` | `TransactionRules.java:28` | `TransactionControllerTest.java:180` (USD, mxn) | USD → 422 `UNSUPPORTED_CURRENCY` |
| RN04 (heredada, observada en HTTP) | regla violada no llama al proveedor | `TransactionService.java:47` | `TransactionControllerTest.java:180` (`verifyNoInteractions(provider)`) | WireMock: 5 llamadas = 5 creadas |
| RN07-accountId | filtro `accountId` | `TransactionSearchCriteria.java:22`, `TransactionController.java:58` | `TransactionSearchIT.java:53`, `TransactionControllerTest.java:351` | `?accountId=acc-777…` → 1 elemento |
| RN07-status | filtro `status` | `TransactionSearchCriteria.java:25` | `TransactionSearchIT.java:65`; inválido `TransactionControllerTest.java:431` | `?status=DONE` → 400 |
| RN07-type | filtro `type` | `TransactionSearchCriteria.java:28` | `TransactionSearchIT.java:77`, `:88` (AND) | `…&type=CREDIT` → 200 |
| RN07-page | paginación `page` (base 0) | `TransactionController.java:62`, `TransactionService.java:71` | `TransactionControllerTest.java:351`, `:385`, `:416`; `TransactionSearchIT.java:113`, `:139` | `?page=1&limit=2` → página 1 |
| RN07-limit | `limit` máx. 100 | `TransactionController.java:66-68` | `TransactionControllerTest.java:401` (100 → 200), `:416` (101/0 → 400) | `?limit=101` → 400 |
| RN07-orden | orden estable | `TransactionService.java:28` | `TransactionSearchIT.java:113` (empates de `createdAt`), `TransactionServiceTest.java:348` | `?page=1&limit=2` orden desc |

## Auditoría Java L3

| Regla | Estado | Evidencia (`archivo:línea`) | Nota |
|---|---|---|---|
| A1 | ✅ | `transaction/TransactionController.java:25`, `transaction/dto/`, `common/web/` | Paquetes de `docs/architecture.md` |
| A2 | ✅ | `TransactionController.java:49-51`, `:70-71` | Controller solo traduce DTO ↔ comando/resultado y elige 200/201 |
| A3 | N/A | — | Puerto sin cambios |
| A4 | ✅ | `ArchitectureTest.java` (`domainDoesNotDependOnProviderHttp`) | `transaction.dto` y controller no tocan `provider.http` |
| A5 | N/A | — | Sin cambios en el puerto |
| A6 | ✅ | `TransactionService.java:70` | Consulta en el servicio existente; sin interfaces nuevas |
| A7 | ✅ | `dto/CreateTransactionRequest.java:18`, `dto/TransactionResponse.java:14` | Records; la entidad no sale en la API (`TransactionResponse.from`) |
| B1 | ✅ | `TransactionRules.java:17` | Sin cambios; no depende de web |
| B2 | ✅ | `TransactionRules.java:22,25` | `compareTo`; DTO usa `BigDecimal` |
| B3 | ✅ | `Transaction.java` sin setters | Tests usan `ReflectionTestUtils` solo para id/fecha que asigna la BD |
| B4 | N/A | — | Estados sin cambios |
| B5 | ✅ | `ApiExceptionHandler.java:60-68` | `code` estable en inglés, `detail` en español |
| C1 | N/A | — | Sin migraciones nuevas |
| C2 | N/A | — | Sin cambios en el flujo |
| C3 | ✅ | `TransactionController.java:21-25` (sin `@Transactional`), `ArchitectureTest.java:137` | Regla transitiva alcanza al controller |
| C4 | ✅ | `TransactionController.java:46-47`, `TransactionService.java:89-94` | Header opcional, máx. 100; replay → 200 |
| C5 | ✅ | `ApiExceptionHandler.java:65` | 409 `IDEMPOTENCY_KEY_CONFLICT` (`TransactionControllerTest.java:213`) |
| C6 | ✅ | `TransactionService.java:28`, `V1__create_transactions.sql:22-25` | `createdAt desc, id desc`; probado con empates (`TransactionSearchIT.java:113`) |
| C7 | N/A | — | Sin cambios |
| D1–D5 | N/A | — | Adaptador sin cambios |
| E1 | ✅ | `CreateTransactionRequest.java:19-33`, `TransactionController.java:45-47,63-68` | Formato = 400; RN01–RN03 = 422 |
| E2 | ✅ | `ApiExceptionHandler.java:45`, `:127-146` | Único advice; JSON inválido, tipo incorrecto y 405 también con `code` |
| E3 | ✅ | `ApiExceptionHandler.java:70-74` | `TransactionControllerTest.java:313`, `:328` |
| E4 | ✅ | `TransactionController.java:50` | `TransactionControllerTest.java:114` (REJECTED → 201) |
| E5 | ✅ | `common/web/PageResponse.java:12` | `TransactionControllerTest.java:351` (conjunto exacto de claves) |
| E6 | ✅ | `TransactionController.java:29,66-68` | 101 → 400 |
| E7 | ✅ | springdoc en pom | `OpenApiIT.java:40`, `:55` |
| F1–F2 | N/A | — | Sin cambios de config |
| F3 | ✅ | `ApiExceptionHandler.java:72` | SLF4J parametrizado; no se loguea el body |
| G1 | ✅ | `TransactionServiceTest.java:348` | |
| G2 | N/A | — | Adaptador sin cambios |
| G3 | ✅ | `TransactionControllerTest.java` | 200, 201, 400, 405, 409, 422, 500 + forma del ProblemDetail |
| G4 | ✅ (parcial) | `TransactionSearchIT.java`, `OpenApiIT.java` | Flujo completo y concurrencia son feature 6 |
| G5 | ✅ | `ArchitectureTest.java:137` | |
| G6 | ✅ | `TransactionControllerTest.java:180,201` | 1.00, 1.01, 10000.00, 10000.01 en HTTP |
| G7 | ✅ | `@DisplayName` en español en todos los tests nuevos | Afirman status, `code`, campos, llamadas al proveedor |
| H1 | ✅ | records `TransactionExecution`, `TransactionSearchCriteria`, DTOs, pattern matching en `ApiExceptionHandler.java:91,106,118,131` | |
| H2 | ✅ | `TransactionController.java:35`, constructores en tests | |
| H3 | ✅ | — | Sin Lombok/MapStruct |
| H4 | ✅ | `TransactionService.java:27`, `TransactionController.java:30`, `ApiExceptionHandler.java:113,170` | Solo "por qué" |
| H5 | ✅ | — | Sin `System.out`, TODOs ni código muerto |

## Commits sugeridos

```
feat(transaction): expone POST y GET /transactions con validación de formato y paginación (RN01–RN03, RN07)
feat(web): agrega contrato de errores RFC 9457 con code estable y lista de errores en 400
test(transaction): cubre códigos HTTP con WebMvcTest, consulta paginada con Postgres y OpenAPI
```

## Vuelta 2 — E7 (CHANGES_REQUESTED de `review_api_endpoints.md`)

### Cambios
- `src/main/java/mx/gilortiz/transactions/transaction/TransactionController.java:48-72` — `POST` documentado con `io.swagger.v3.oas.annotations.responses.ApiResponse` (viene con springdoc, sin dependencia nueva):
  - 201 `application/json` → `TransactionResponse` ("creada; el status indica el resultado del proveedor, también si la rechazó").
  - 200 `application/json` → `TransactionResponse` ("repetición de Idempotency-Key; puede seguir PENDING").
  - 400 / 409 / 422 / 500 `application/problem+json` → `ProblemDetail`, con los `code` posibles en la descripción.
  - El éxito ya no se publica como `*/*`.
- `TransactionController.java:85-96` — `GET` con `produces = application/json` (sin él springdoc publica el 200 como `*/*`) + 400 y 500 `application/problem+json` → `ProblemDetail`. Los errores no se ven afectados: Spring ignora los tipos producibles del handler al resolver excepciones (los 15 casos GET de `TransactionControllerTest` siguen verdes, incluidos los 400).
- `src/test/java/mx/gilortiz/transactions/OpenApiIT.java:56` — nuevo `apiDocsDescribePostResponses`: `paths./transactions.post.responses` tiene exactamente 200, 201, 400, 409, 422, 500; 201 y 200 referencian `#/components/schemas/TransactionResponse`; los errores tienen `application/problem+json`; ninguna respuesta de POST ni GET usa `*/*`; GET 200 referencia `PageResponseTransactionResponse` y GET 400 es problem+json.

### Decisión confirmada por el leader
No se exponen `code`/`message` del rechazo en el response (literalidad de la spec).

### Verificación
`./.harness/init.sh` → exit 0, `[OK] verify verde`. Surefire 192, Failsafe 20 (OpenApiIT 3), total **212**, 0 fallos.

### Auditoría L3 (fila actualizada)

| Regla | Estado | Evidencia | Nota |
|---|---|---|---|
| E7 | ✅ | `TransactionController.java:48-72,85-96`, `OpenApiIT.java:56` | Respuestas 200/201/400/409/422/500 documentadas con su esquema y media type |

### Commit sugerido adicional
```
docs(api): documenta en OpenAPI las respuestas de POST y GET /transactions
```
