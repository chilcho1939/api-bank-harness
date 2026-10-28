# /code-review (medium) — feature 6 integration — triage del leader

| # | Archivo:línea | Hallazgo | Decisión |
|---|---|---|---|
| 1 | TransactionFlowIT:347 | Holgura insuficiente entre MAX_WAIT_WHILE_HELD (3s) y read-timeout (4s): en CI lento los retenidos dan UNKNOWN y fallan los tests de concurrencia. | **Corregir:** separar el escenario de timeout en su propio IT (contexto con read-timeout corto, p. ej. 1–2s) y dar a `TransactionFlowIT` un read-timeout amplio (≥10s) para que la espera bajo gate nunca lo roce. Sin alargar la suite de forma notable. |
| 2 | TransactionFlowIT:386 | Orden esperado con `UUID.compareTo` (signed) ≠ `id desc` de Postgres (unsigned) en empates de createdAt. | **Corregir:** derivar el orden esperado de la BD (`ORDER BY created_at DESC, id DESC`) o forzar createdAt distintos; no usar `UUID.compareTo`. |
| 3 | PostgresTestConfiguration:19 | Contenedor arrancado en inicializador estático → `ExceptionInInitializerError`/`NoClassDefFoundError` opacos. | **Corregir:** arranque perezoso (holder + start en el método del bean) para que el error real de Testcontainers aparezca en cada IT. |
| 4 | PostgresTestConfiguration:14 | Sin Ryuk, el contenedor compartido queda vivo. | **Aceptar:** Ryuk está activo por defecto en local y en GitHub Actions; documentar en el reporte. |
| 5 | ApplicationContextIT:34 | "tabla vacía" → "tabla existe" pierde detectar una migración que siembre filas. | **Aceptar:** con BD compartida "vacía" no es estable; una siembra en V1 se ve en el review de la migración. Documentar. |
| 6 | TransactionFlowIT:144 | `@AfterEach` espera al journal de WireMock y puede fallar/ocultar el error real. | **Corregir:** limpieza best-effort acotada que no lance (o que no dependa del journal tras read-timeout). |
| 7 | TransactionFlowIT:262 | Gate abierto en 3 lugares solapados. | **Corregir:** un solo responsable (el `@AfterEach`), salvo donde el test lo necesite explícitamente para su aserción. |
| 8 | TransactionFlowIT:471 | `COUNT(*)` a mano teniendo `repository.count()`. | **Corregir:** usar el repositorio. |
| 9 | TransactionFlowIT:139 | Cada IT limpia la tabla compartida a su manera. | **Corregir:** una sola limpieza compartida (base común o helper junto a `PostgresTestConfiguration`) usada por todos los IT que escriben. |
