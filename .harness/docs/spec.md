# Especificación — Transaction Execution API

> Contrato funcional del reto. Fuente original: `../Backend Challenge (1).pdf` (un directorio arriba del repo).
> Si este archivo y el PDF difieren, **gana el PDF** y se corrige este archivo.

## Objetivo

API REST que ejecuta transacciones financieras (`CREDIT` y `DEBIT`):

1. Recibe la solicitud.
2. La ejecuta contra un proveedor externo, dueño de saldos y bloqueos.
3. Persiste el resultado.
4. Expone una consulta de transacciones.

**Contexto:** alto volumen (millones de transacciones diarias). El diseño debe considerar escalabilidad y rendimiento.
**Fuera de alcance:** gestión de cuentas y saldos. El servicio solo ejecuta, registra y expone.

## Endpoint 1 — `POST /transactions`

Request:

```json
{
  "accountId": "acc-123456",
  "type": "CREDIT",
  "amount": 1500.00,
  "currency": "MXN",
  "description": "Transferencia recibida"
}
```

Flujo obligatorio, en este orden:

1. Validar el formato del request.
2. Aplicar las reglas de negocio (RN01–RN03).
3. Llamar al proveedor externo.
4. Persistir la transacción con el resultado del proveedor, incluido el saldo que devuelve.
5. Responder la transacción creada con su estado.

Response (ejemplo):

```json
{
  "id": "uuid-generado",
  "accountId": "acc-123456",
  "type": "CREDIT",
  "amount": 1500.00,
  "currency": "MXN",
  "description": "Transferencia recibida",
  "status": "EXECUTED",
  "providerTransactionId": "txn-789",
  "balanceAfter": 5500.00,
  "createdAt": "2025-03-15T10:30:00Z"
}
```

## Endpoint 2 — `GET /transactions`

Query params opcionales: `accountId`, `status`, `type`, `page`, `limit`.
Devuelve las transacciones almacenadas. Se valoran filtros y paginación.

## Proveedor externo — `POST /provider/v1/execute`

No se implementa: se simula (WireMock, mock server o stub configurable).

Request:

```json
{ "accountId": "acc-123456", "type": "CREDIT", "amount": 1500.00, "currency": "MXN" }
```

Éxito (HTTP 200):

```json
{ "transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00, "executedAt": "2025-03-15T10:30:00Z" }
```

Falla (HTTP 4xx–5xx):

```json
{ "status": "REJECTED", "code": "INSUFFICIENT_FUNDS", "message": "The account does not have enough balance to complete the transaction" }
```

Ambos escenarios deben reflejarse en la transacción persistida.

## Reglas de negocio

Se validan **en el servicio, antes** de llamar al proveedor. Si una falla, la transacción no se ejecuta y el cliente recibe un error descriptivo.

| ID | Regla | Sub-cláusulas verificables |
|----|-------|----------------------------|
| RN01 | Monto mínimo | `amount > 1.00`; `1.00` exacto se rechaza; aplica a CREDIT y DEBIT |
| RN02 | Monto máximo DEBIT | DEBIT `≤ 10,000.00`; `10,000.00` exacto se acepta; CREDIT sin límite |
| RN03 | Moneda | Solo `MXN`; cualquier otra se rechaza |
| RN04 | Orden | Si RN01–RN03 fallan, el proveedor **no** recibe la llamada |
| RN05 | Resultado aprobado | Se persiste con `providerTransactionId` y `balanceAfter` del proveedor |
| RN06 | Resultado rechazado | 4xx/5xx del proveedor se persiste con su `code` y `message` |
| RN07 | Consulta | Filtros `accountId`, `status`, `type`; paginación `page`, `limit` |

## Requerimientos técnicos y entregables

| ID | Requerimiento |
|----|---------------|
| RT01 | Persistencia a elección, **justificada en el README** |
| RT02 | Al menos tests unitarios; el proveedor se prueba con mocks |
| RT03 | README con instrucciones para levantar y ejecutar, y decisiones de diseño |
| RT04 | Nota en el README sobre el uso de IA y de qué forma |
| RT05 | Código en un repositorio Git |

Extras considerados: más tests, Dockerfile, documentación adicional, OpenAPI.

## Qué se valora

- Código limpio: nombres claros, responsabilidad única, código que comunica intención.
- Arquitectura: separación de capas, inversión de dependencias, estructura coherente.
- Testing: cobertura razonable del dominio y mocks del proveedor.
- Proveedor: cliente HTTP desacoplado; resiliencia (timeouts, reintentos, circuit breaker) es un plus.
- **Pragmatismo: no sobre-ingenierizar.** Simple y extensible.
- Documentación: README útil y decisiones explicadas.
