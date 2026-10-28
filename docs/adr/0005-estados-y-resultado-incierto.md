# ADR-0005: Registrar `PENDING` antes del proveedor y distinguir el resultado incierto (`UNKNOWN`)

**Estado:** Aceptada

## Contexto

La spec exige persistir la transacción "con el resultado del proveedor" en ambos escenarios (RN05, RN06), y el proveedor es dueño de los saldos: una vez que recibe la petición, la operación puede aplicarse aunque nunca recibamos la respuesta. Tres riesgos:

- El proceso muere entre la llamada y el guardado: el proveedor movió dinero y no hay rastro.
- Un timeout se registra como "falló" y alguien la reintenta: dinero duplicado.
- Una transacción de BD abierta durante la llamada HTTP retiene una conexión hasta 3 s; con alto volumen se agota el pool.

## Decisión

- **Estados**: `PENDING`, `EXECUTED`, `REJECTED`, `FAILED`, `UNKNOWN` (enum `TransactionStatus` y `CHECK` en la BD).

  | Resultado del puerto | Estado | Cuándo |
  |---|---|---|
  | `Approved` | `EXECUTED` | 2xx `APPROVED` completo; guarda `providerTransactionId` y `balanceAfter` (RN05) |
  | `Rejected` | `REJECTED` | 4xx o 5xx con cuerpo `{status, code, message}`; guarda `code` y `message` (RN06) |
  | `Failed` | `FAILED` | Seguro que no se aplicó: conexión rechazada tras reintentos, circuito abierto, 500/502/503 sin cuerpo, 4xx sin cuerpo |
  | `Unknown` | `UNKNOWN` | Pudo aplicarse: timeout de lectura, 504 sin cuerpo, conexión cortada, respuesta inválida, error inesperado |

  `FAILED` y `UNKNOWN` también guardan un `code` técnico (`PROVIDER_ERROR`, `PROVIDER_TIMEOUT`…) y un mensaje.
- **Flujo en tres pasos cortos, sin `@Transactional` en el servicio**: reglas → `INSERT PENDING` (`saveAndFlush`, su propia transacción) → proveedor **sin transacción ni conexión** → `UPDATE … SET estado final WHERE id = ? AND status = 'PENDING'` (`completePending`, su propia transacción). `open-in-view: false`. ArchUnit prohíbe `@Transactional` en cualquier clase que dependa del puerto.
- **Transición única**: la entidad solo cambia desde `PENDING` (métodos `markExecuted/markRejected/markFailed/markUnknown`, sin setters) y el `UPDATE` dirigido lo garantiza también en la BD. Si actualiza 0 filas, es un error del servidor (500) y se registra en el log.
- **No perder el resultado**: si el `UPDATE` final falla, se registra un `ERROR` con id, estado, `providerTransactionId` y `code` (sin monto ni cuenta), y la fila queda `PENDING` para conciliar.
- **Una aprobación que no cabe en las columnas** (id > 100, saldo con más de 2 decimales o 17 enteros) pasa a `UNKNOWN` con `PROVIDER_INVALID_RESPONSE`, conservando los datos del proveedor en `message`.
- **`POST` responde 201 aunque el proveedor rechace o falle**: la transacción se creó; el `status` comunica el resultado.

## Alternativas consideradas

- **Dos estados (éxito/fallo)**: obliga a mentir en el timeout; un `FAILED` que en realidad se aplicó invita a reintentar y duplicar.
- **Timeout → `FAILED`**: mismo problema; un timeout no prueba que la operación no se aplicó.
- **Persistir solo después de llamar al proveedor**: si el proceso muere a la mitad no queda rastro.
- **Una transacción de BD que envuelva todo**: atómico en apariencia (el proveedor no participa de la transacción) y retiene una conexión durante la llamada.
- **Responder 502/504 cuando el proveedor falla**: el recurso sí se creó y queda consultable; el cliente necesita su `id` para conciliar.

## Consecuencias

- Toda llamada al proveedor deja una fila consultable con su estado, incluso si el proceso se cae.
- `PENDING` que no avanza y `UNKNOWN` necesitan **conciliación** con el proveedor (consultar por `providerTransactionId` o por cuenta/monto/fecha). Esa tarea está fuera del alcance del reto; queda documentada en el README.
- La respuesta no expone `code`/`message` (la spec no los incluye en el response, ver ADR-0008): el cliente ve `REJECTED`, pero el motivo queda solo en la BD.
- El estado tarda hasta `read-timeout` (3 s) en ser definitivo cuando el proveedor está lento.
