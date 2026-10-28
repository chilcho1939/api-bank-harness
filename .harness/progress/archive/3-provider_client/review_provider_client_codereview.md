# /code-review (medium) — feature 3 provider_client — triage del leader

Hallazgos del `/code-review` con la decisión del leader. "Corregir" = el implementer lo resuelve en la vuelta 2.

| # | Archivo:línea | Hallazgo | Decisión |
|---|---|---|---|
| 1 | HttpTransactionProvider:159 | 504 (y gateway 5xx) sin cuerpo → Failed, pero no prueba que el proveedor no aplicó la operación (B4). | **Corregir:** 504 sin cuerpo → `Unknown` (sin retry; cuenta como falla técnica para el circuito). 500/502/503 sin cuerpo siguen → `Failed` (acceptance literal "5xx sin cuerpo → falla"). Test WireMock 504. Documentar en reporte. |
| 5 | HttpTransactionProvider:160 | 5xx **con cuerpo** `{status:REJECTED, code, message}` → Failed y cuenta para el circuito. | **Corregir (spec):** RN06 dice "4xx/5xx del proveedor se persiste con su `code` y `message`" → 4xx o 5xx con cuerpo parseable de rechazo → `Rejected(code, message)`, no cuenta para el circuito. Test WireMock 500 con cuerpo. |
| 2 | HttpTransactionProvider:173 | Si readTimeout ≤ connectTimeout, un connect stall se clasifica como read timeout (Unknown, sin retry). | **Corregir:** validar en `ProviderProperties` que `readTimeout > connectTimeout` (falla al arrancar con mensaje claro). Test unitario. |
| 3 | HttpTransactionProvider:130 | Contrato "no lanza excepciones" pero solo captura `RestClientException`. | **Corregir:** cualquier otra `RuntimeException` → `Unknown` (código estable, p. ej. `PROVIDER_UNEXPECTED_ERROR`) con log de error; no cuenta como reintento. Test con causa forzada si es barato. |
| 4 | ArchitectureTest:49 | Regla `@Transactional` solo anotaciones directas; no meta-anotaciones ni capas superiores. | **Corregir parcial:** usar `isMetaAnnotatedWith`/`isAnnotatedWith` que cubra meta-anotaciones. La cobertura de llamadores (controller/facade) queda para feature 4 (anotado en history). |
| 6 | HttpTransactionProvider:69 | `RestClient.builder()` estático, sin el `RestClient.Builder` de Boot (observabilidad, Jackson). | **Corregir:** inyectar el `RestClient.Builder` de Boot y clonarlo. |
| 7 | ExecuteResponse:23 | APPROVED sin `executedAt` → Approved con null. | **Corregir:** `executedAt` es parte del contrato literal de éxito; sin él → `Unknown(PROVIDER_INVALID_RESPONSE)`. Test. |
| 8 | ProviderProperties:30 | Primitivos de retry/CB caen a 0 si faltan; error críptico. | **Corregir:** `@Validated` + `@Min`/`@Positive` (o validación en constructor compacto) con mensaje que nombre la propiedad. |
| 9 | HttpTransactionProvider:109 | Log "tras {} intentos" usa el configurado, no el real. | **Corregir trivial:** quitar el número o usar métricas del retry. |
| 10 | HttpTransactionProvider:78 | Retry con espera fija, sin backoff/jitter. | **Corregir:** `IntervalFunction.ofExponentialRandomBackoff` con valores en properties; tests siguen rápidos. |
