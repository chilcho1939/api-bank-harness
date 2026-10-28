# ADR-0007: Idempotencia opcional con `Idempotency-Key` garantizada por un `UNIQUE` en la BD

**Estado:** Aceptada

## Contexto

La spec no define idempotencia, pero un cliente que no recibe respuesta (timeout de red, caída del balanceador) reintenta el `POST`, y cada reintento ejecutaría otro movimiento en el proveedor. Con varias réplicas, una verificación en memoria no protege de dos requests simultáneas.

## Decisión

- **Header opcional `Idempotency-Key`** (máx. 100 caracteres → si no, 400). Sin header, o en blanco, cada `POST` es una transacción nueva.
- La llave se guarda en `transactions.idempotency_key` con **`UNIQUE`**: la BD es el árbitro entre réplicas.
- Flujo: reglas RN01–RN03 → buscar la llave → si no existe, `INSERT PENDING`.
  - **Misma llave y mismo payload** → se devuelve la transacción existente con **200** (la creación original fue 201), sin llamar al proveedor.
  - **Misma llave y payload distinto** → **409 `IDEMPOTENCY_KEY_CONFLICT`**. El payload comparado son los 5 campos del request: `accountId`, `type`, `amount` (con `compareTo`: `1500.0` = `1500.00`), `currency` y `description`.
  - **Carrera** (dos requests pasan la búsqueda a la vez): la perdedora recibe la violación del `UNIQUE` al hacer `saveAndFlush`, relee la ganadora por llave y aplica la misma comparación. Probado con 10 requests concurrentes: un 201, nueve 200 con el mismo id, 1 fila y 1 llamada al proveedor (`TransactionFlowIT`).
- **Duplicado en vuelo**: si la ganadora aún espera al proveedor, la repetición recibe 200 con `status: PENDING`, sin bloquear ni volver a llamar.
- **Un resultado `FAILED` o `UNKNOWN` consume la llave**: el replay devuelve ese resultado; para reintentar, el cliente usa una llave nueva.
- Las reglas se evalúan antes que la llave: una repetición que viola una regla recibe el 422 de la regla.

## Alternativas consideradas

- **Bloqueo en memoria o `synchronized`**: no funciona entre réplicas.
- **Redis/lock distribuido**: otra pieza de infraestructura para algo que el `UNIQUE` ya resuelve.
- **Llave obligatoria**: la spec no la menciona en el request; hacerla obligatoria rompería el contrato.
- **409 o 202 para el duplicado en vuelo**: obliga al cliente a otro manejo; 200 con `PENDING` ya comunica que sigue en curso y no hay endpoint por id para hacer polling.
- **Permitir reintentar con la misma llave tras `FAILED`**: `FAILED` y `UNKNOWN` no se distinguen con seguridad sin conciliar; reintentar tras un `UNKNOWN` podría duplicar dinero.
- **Guardar un hash del payload**: innecesario; los campos ya están en columnas.

## Consecuencias

- Reintentar con la misma llave es seguro: el proveedor recibe una sola llamada por llave.
- La llave no expira (no hay TTL): una llave reutilizada meses después devuelve la transacción original o 409.
- El cliente que recibe `PENDING` en un replay debe consultar después (`GET /transactions?accountId=…`) o repetir con la misma llave.
