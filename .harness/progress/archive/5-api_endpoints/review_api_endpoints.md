# Review — feature 5 api_endpoints (vuelta 1)

> Guardado por el leader: el reviewer no pudo escribir el archivo (permiso denegado) y devolvió el contenido en su respuesta.

**Veredicto:** CHANGES_REQUESTED

**Cobertura:** 25/26 reglas verificadas. Falla E7: el OpenAPI generado documenta mal `POST /transactions`.

## Verificación ejecutada por el reviewer

- `./mvnw verify`: BUILD SUCCESS. Surefire 192/0/0/0; Failsafe 19/0/0/0 (TransactionControllerTest 37, TransactionServiceTest 25, ArchitectureTest 10, TransactionSearchIT 7, OpenApiIT 2).
- `./.harness/init.sh`: EXIT=0.
- Smoke HTTP aislado (Postgres :55440, WireMock :58190, jar :58180), sin tocar contenedores `api-bank-challenge-*`; limpiado al final.
- `git diff main` completo; `pom.xml` sin cambios.

## Matriz de reglas (resumen)

| # | Regla | Resultado |
|---|---|---|
| 1 | POST 201 con campos literales (`id, accountId, type, amount, currency, description, status, providerTransactionId, balanceAfter, createdAt`), sin campos inventados | PASS |
| 2 | Rechazo del proveedor → 201 REJECTED | PASS |
| 3 | `Idempotency-Key` opcional, máx. 100 (101 → 400; en blanco → 201) | PASS |
| 4 | 400 ProblemDetail con lista de errores; JSON roto sin mensaje de Jackson | PASS |
| 5–10 | RN01–RN03 fronteras (1.00/1.01, DEBIT 10000.00/10000.01, CREDIT sin límite, solo MXN) | PASS |
| 11 | RN04 observada: WireMock recibió solo las 9 llamadas de respuestas 201 | PASS |
| 12 | 409 conflicto de llave | PASS |
| 13 | 500 sin detalles internos (BD en pausa) | PASS |
| 14 | Replay 200 / creada 201 / duplicado en vuelo PENDING → 200 sin proveedor | PASS |
| 15 | `@Digits(17,2)` → 400 | PASS |
| 16–21 | RN07: filtros, page/limit validados, orden estable con desempate, paginación en BD | PASS |
| 22 | `/v3/api-docs` y `/swagger-ui.html` | PASS |
| 23 | **E7: el OpenAPI refleja el contrato.** `post.responses` = solo `200 */*`; no documenta 201 ni 400/409/422/500 | **FAIL** |
| 24 | `@WebMvcTest` de cada código HTTP | PASS |
| 25 | ArchUnit: controller sin `@Transactional`; paquetes | PASS |
| 26 | Tests de f4 adaptados a `TransactionExecution` sin perder aserciones | PASS |

## Estándar Java L3

A7, C3, C6, E2, E3, E5, E6, H2: PASS. **E7: FAIL** (fila 23).

## Checkpoints

- C1 [x], C2 [x], C3 [x]
- C4 [ ] falta test que fije el contrato OpenAPI (E7)
- C5 [ ] pendiente de cierre

## Cambios requeridos

1. Documentar las respuestas de `POST /transactions` en `TransactionController.java:43-52` con `io.swagger.v3.oas.annotations` (vienen con springdoc):
   - 201 creada, aunque el proveedor la rechace (`TransactionResponse`).
   - 200 replay de `Idempotency-Key`; puede venir PENDING (`TransactionResponse`).
   - 400, 409, 422 y 500 como `application/problem+json` (`ProblemDetail`).
   - Quitar el `*/*` del éxito. En `GET`, documentar el 400.
2. Extender `OpenApiIT.java:40-51` para afirmar que `paths./transactions.post.responses` contiene 201, 200, 400, 409, 422 y 500, y que 201 usa el esquema `TransactionResponse`.

## Notas (no bloqueantes)

- `docs/sonar-decisiones.md` no entra en los commits de f5.
- Cada página hace `count(*)`; el README (f7) puede mencionar paginación por keyset.
- Replay con regla violada → 422 (reglas antes que idempotencia), coherente con RN04.

---

# Review — feature 5 api_endpoints (vuelta 2)

> Guardado por el leader (el reviewer no tiene permiso de escritura).

**Veredicto:** APPROVED — 26/26. E7 pasa.

- `init.sh` EXIT=0. Surefire 192, Failsafe 20 (OpenApiIT 3), 0 fallos, 0 skipped.
- `/v3/api-docs` real: POST 201/200 `application/json` → `TransactionResponse`; 400/409/422/500 `application/problem+json` → `ProblemDetail`; GET 200 → `PageResponseTransactionResponse`, 400/500 `problem+json`. Sin `*/*`. `TransactionResponse` con los 10 campos de la spec.
- Fijado por `OpenApiIT.java:56-77`. Anotaciones en `TransactionController.java:49-95`; `pom.xml` sin cambios.
- Cambio extra `@GetMapping(produces = application/json)`: aceptable. Con `*/*` o `application/json` idéntico a vuelta 1; solo `Accept: application/problem+json` → 406 `problem+json` con `code: NOT_ACCEPTABLE`.
- Smoke de regresión: 201/422/400/200 replay/409/GET filtrado OK; WireMock recibió solo las 2 llamadas de transacciones creadas (RN04).
- Checkpoints C1–C4 [x]; C5 al cierre del leader.

**Cambios requeridos:** ninguno.

**Notas:** `docs/sonar-decisiones.md` fuera de f5; test opcional del 406; `count(*)`/keyset al README (f7).
