# /code-review (medium) — feature 4 execution_service — triage del leader (pendiente de aplicar)

| # | Archivo:línea | Hallazgo | Decisión propuesta |
|---|---|---|---|
| 1 | TransactionService:52 | Si falla el save final tras llamar al proveedor, queda PENDING sin log del resultado. | Corregir: log ERROR estructurado con resultado del proveedor (id, status, code) + rethrow; PENDING queda para conciliación (documentado). |
| 2 | Transaction:134 | Solo se trunca `message`; code/providerTransactionId/balance pueden exceder columnas. | Corregir: truncar/validar `code` y `providerTransactionId`; balance fuera de rango → Unknown(PROVIDER_INVALID_RESPONSE) antes de persistir. |
| 3 | Transaction:121 | amount con >2 decimales: proveedor recibe 1500.005, BD guarda 1500.01 → replay da conflicto. | Corregir: rechazar escala >2 (regla/validación) antes de persistir; Bean Validation `@Digits` en feature 5. |
| 4 | TransactionService:40 | Replay devuelve FAILED; la llave queda quemada. | Aceptar y documentar (semántica estándar de Idempotency-Key: la llave identifica el intento; reintentar = llave nueva). |
| 5 | TransactionService:66 | Duplicado concurrente devuelve ganadora PENDING. | Aceptar y documentar; feature 5 decide código HTTP (p. ej. 409/202 para PENDING en curso). |
| 6 | TransactionService:56 | Idempotency-Key en blanco = llave real compartida. | Corregir: blank → null. |
| 7 | TransactionService:52 | save sobre entidad detached = merge (SELECT extra). | Corregir: UPDATE dirigido `WHERE id=? AND status='PENDING'` o mantener managed; refuerza transición única. |
| 8 | ArchitectureTest:42 | Regla transitiva a nivel clase rompería GET readOnly futuro. | Aceptar por ahora; revisar en feature 5 si el controller necesita `@Transactional(readOnly)` (no debería: el repo ya es transaccional). |
