# ADR-0004: Persistir en PostgreSQL con una tabla `transactions` versionada con Flyway

**Estado:** Aceptada

## Contexto

RT01 pide persistencia "a elección, justificada". Lo que el servicio necesita de la BD:

- Escritura atómica y duradera de cada transacción **antes** de llamar al proveedor (`PENDING`) y su actualización posterior.
- Una garantía de unicidad entre réplicas para `Idempotency-Key` (ADR-0007).
- Una transición de estado condicional (`PENDING` → final una sola vez) que no dependa de la memoria de una instancia.
- Consultas con filtros `accountId`, `status`, `type` y paginación estable (RN07).
- Dinero exacto.

## Decisión

- **PostgreSQL 17** con Spring Data JPA (Hibernate). Esquema con **Flyway** (`V1__create_transactions.sql`) y `ddl-auto: validate`.
- **Una sola tabla `transactions`**: columnas en `snake_case` de los campos literales del response (`account_id`, `type`, `amount`, `currency`, `description`, `status`, `provider_transaction_id`, `balance_after`, `created_at`) más `code` y `message` del proveedor (RN06) e `idempotency_key`.
- **Dinero en `NUMERIC(19,2)`** ↔ `BigDecimal`; el request admite como máximo 2 decimales (`@Digits(17,2)` → 400) para que lo que recibe el proveedor sea idéntico a lo que se guarda.
- **Restricciones en la BD**: `CHECK` de `type` y `status`, `UNIQUE (idempotency_key)` (los `NULL` no colisionan).
- **Índices alineados con RN07** con el orden de la paginación como sufijo: `(created_at, id)`, `(account_id, created_at, id)`, `(status, created_at, id)`, `(type, created_at, id)`. La consulta ordena `created_at DESC, id DESC`.
- **IDs UUID v7 generados en la aplicación** (`@UuidGenerator(style = VERSION_7)`): sin ida y vuelta a la BD y ordenables por tiempo, con buena localidad en el índice primario.
- `created_at` en `TIMESTAMPTZ`, truncado a microsegundos en la app (la respuesta coincide con lo releído); Hibernate en UTC.
- Paginación por offset (`page`, `limit` ≤ 100) con conteo total.

## Alternativas consideradas

- **MongoDB / DynamoDB**: escalan horizontalmente, pero la unicidad condicional y la transición `UPDATE … WHERE status = 'PENDING'` requieren mecanismos específicos; el modelo es tabular y de esquema fijo.
- **H2 / BD en memoria**: no persiste y difiere de Postgres en tipos y concurrencia; descartada también para tests (Testcontainers).
- **Hash del payload en una columna para la idempotencia**: los cinco campos ya son columnas; compararlos directamente evita una columna derivada.
- **Tabla separada de llaves de idempotencia**: un JOIN y una segunda escritura más sin necesidad; la llave pertenece a la transacción.
- **Paginación keyset** (`WHERE (created_at, id) < (?, ?)`): mejor para páginas profundas, pero la spec pide `page`/`limit`; queda como mejora.
- **`BIGSERIAL` como id**: requiere la BD para generarlo y expone un contador.

## Consecuencias

- Transacciones ACID y restricciones que protegen la integridad aunque haya varias réplicas de la app.
- Una sola instancia de Postgres es el cuello de botella de escritura; a millones/día (decenas de TPS promedio) es holgado, y existen caminos conocidos: réplicas de lectura para el GET, particionado por `created_at`, PgBouncer.
- Cada página hace un `count(*)` además de la consulta; con tablas muy grandes y filtros poco selectivos es caro (ver README, mejoras).
- Cuatro índices encarecen cada escritura; se aceptan porque cubren exactamente los filtros de RN07.
