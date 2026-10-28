# ADR-0008: Errores en RFC 9457 (`ProblemDetail`) con `code` estable y 400/409/422/500 diferenciados

**Estado:** Aceptada

## Contexto

La spec pide que, si una regla de negocio falla, "el cliente recibe un error descriptivo", pero no define el formato. El cliente necesita distinguir un error de formato, una regla violada, un conflicto de idempotencia y una falla del servidor, y programar contra algo más estable que un texto en español.

## Decisión

- Un único `@RestControllerAdvice` (`ApiExceptionHandler`, extiende `ResponseEntityExceptionHandler`) produce `application/problem+json` (RFC 9457: `type`, `title`, `status`, `detail`, `instance`) con la propiedad extra **`code`** en inglés `UPPER_SNAKE`. `detail` va en español.

  | HTTP | `code` | Cuándo |
  |---|---|---|
  | 400 | `VALIDATION_ERROR` | Bean Validation del body, del header `Idempotency-Key` o de `page`/`limit`; tipo inválido en query (`status=DONE`). Incluye `errors: [{field, message}]` |
  | 400 | `MALFORMED_REQUEST` | JSON ilegible o enum desconocido en el body; nunca se expone el mensaje de Jackson |
  | 409 | `IDEMPOTENCY_KEY_CONFLICT` | Llave reutilizada con otro payload (ADR-0007) |
  | 422 | `AMOUNT_TOO_LOW`, `DEBIT_AMOUNT_EXCEEDS_LIMIT`, `UNSUPPORTED_CURRENCY` | RN01–RN03; el proveedor no se llama (RN04) |
  | 500 | `INTERNAL_ERROR` | Cualquier otra excepción; sin stack trace ni mensaje interno (el detalle va al log) |
  | 405, 406, 415… | nombre del estado (`METHOD_NOT_ALLOWED`…) | Errores propios de Spring MVC |

- **Formato = 400, negocio = 422**: Bean Validation solo cubre obligatoriedad, longitudes de columna y `@Digits(17,2)`. Un monto ≤ 1.00 (incluidos 0 y negativos) o una moneda distinta de `MXN` (incluida `mxn`) llegan a las reglas y responden 422 con el código de la regla.
- **El rechazo del proveedor no es un error HTTP**: 201 con `status: REJECTED` (ADR-0005).
- **El response no expone `code`/`message` del rechazo del proveedor**: el response de la spec tiene exactamente 10 campos y se respeta literal. Se persisten (RN06).
- Las respuestas se documentan en OpenAPI (springdoc): `POST` 200/201/400/409/422/500, `GET` 200/400/500.

## Alternativas consideradas

- **Sobre de error propio** (`{error, message}`): otro formato que documentar; RFC 9457 es estándar y Spring lo soporta de forma nativa.
- **400 para reglas de negocio**: mezcla "no entiendo tu request" con "lo entiendo pero no lo permito".
- **`@Positive` en el monto / `@Pattern` en la moneda**: convertiría RN01/RN03 en 400 genéricos y perdería el código de la regla.
- **Exponer `code`/`message` del proveedor en el response**: útil para el cliente, pero agrega campos que la spec no define.

## Consecuencias

- Todos los errores tienen la misma forma y un `code` contra el que programar; los tests de controller afirman `code` y `errors`.
- Un cliente no sabe por qué se rechazó (`INSUFFICIENT_FUNDS`) sin otra vía de consulta; se documenta como limitación.
