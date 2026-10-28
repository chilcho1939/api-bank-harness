# Bitácora

> Append-only. Una entrada por feature cerrada: `## YYYY-MM-DD — feature <id> <name>` + 3–5 bullets.

## 2026-10-28 — feature 1 bootstrap

- Spring Boot 4.1.2 / Java 21 con Maven Wrapper; versiones base en `.harness/docs/reference-config.md`.
- Flyway V1 crea `transactions` con UNIQUE en `idempotency_key` e índices para filtros RN07.
- Surefire (`*Test`) y Failsafe (`*IT`) separados; ArchUnit (dominio ↛ `provider.http`, sin `@Autowired` en campos) + IT de contexto con Testcontainers.
- docker compose: postgres :5432 y WireMock :8089. Bloque `provider.*` diferido a feature 3.
- Review APPROVED en 1 vuelta. Pendiente para feature 3/4: regla ArchUnit "sin `@Transactional` alrededor del proveedor".

## 2026-10-28 — feature 2 business_rules

- `TransactionRules` valida RN01 → RN02 → RN03 (primera violación gana) con `BigDecimal.compareTo`.
- `BusinessRuleViolation` (enum): código estable = `name()` en inglés, mensaje en español; `BusinessRuleException` para mapear a 422 en feature 5.
- Moneda comparada literal (`MXN`); normalizar, si se decide, antes de invocar reglas (feature 5).
- JaCoCo fusionado (unitarios + IT) en `target/site/jacoco/jacoco.xml`; `.github/workflows/ci.yml` con `./mvnw verify`; ADR-0001.
- Review APPROVED en 1 vuelta. Desde aquí: una rama + un PR por feature.

## 2026-10-28 — feature 3 provider_client

- Puerto `TransactionProvider` en `transaction` con `ProviderResult` sellado (Approved/Rejected/Failed/Unknown); adaptador `provider/http` con `RestClient` (JDK HttpClient, builder de Boot) y Resilience4j programático.
- Clasificación: 2xx APPROVED → Approved; 4xx/5xx con cuerpo → Rejected (RN06, no cuenta para circuito); 500/502/503 sin cuerpo → Failed; 504 sin cuerpo, read timeout, respuesta inválida o excepción inesperada → Unknown; conexión rechazada → retry (3, backoff exponencial con jitter) → Failed.
- `ProviderProperties` validado (`readTimeout > connectTimeout`, rangos de retry/CB). ArchUnit: sin `@Transactional` (incl. meta-anotaciones) en clases que dependen del puerto.
- Review APPROVED vuelta 1; `/code-review` → 10 hallazgos triados y corregidos; review APPROVED vuelta 2.
- Pendiente feature 4: `@Transactional` en llamadores indirectos (controller/facade). Feature 7: `PROVIDER_BASE_URL=http://provider-mock:8080` en compose.

## 2026-10-28 — feature 4 execution_service

- Entidad `Transaction` (estados PENDING → EXECUTED/REJECTED/FAILED/UNKNOWN, transición única) y `TransactionService`: reglas → INSERT PENDING (tx corta) → proveedor sin tx → UPDATE dirigido `WHERE status='PENDING'` (tx corta).
- Idempotencia: misma llave + mismo payload → existente sin llamar al proveedor; payload distinto → `IdempotencyKeyConflictException`; carrera del UNIQUE → relee ganadora. Llave en blanco = sin llave.
- Monto con >2 decimales rechazado en `ExecuteTransactionCommand`; campos del proveedor acotados a columnas; si falla el UPDATE final se loguea el resultado del proveedor para conciliación.
- Aceptado y documentado: FAILED consume la llave; duplicado en vuelo recibe la ganadora PENDING; ArchUnit transitivo a nivel clase.
- Review CHANGES_REQUESTED → vuelta 2 (reviewer + `/code-review`) → APPROVED.
- Para feature 5: `@Digits(integer=17, fraction=2)` en DTO (400), mapear PENDING en vuelo, conflicto (409) e `IllegalStateException` de 0 filas a ProblemDetail sin detalles internos. Feature 6: IT de concurrencia real y "sin tx durante la llamada".

## 2026-10-28 — feature 5 api_endpoints

- `POST /transactions`: 201 creada / 200 replay (incluye duplicado en vuelo PENDING, sin llamar al proveedor); response con los 10 campos literales de la spec, sin `code`/`message` del rechazo (decisión del leader: la spec no los expone; se persisten por RN06).
- `ApiExceptionHandler` (RFC 9457) con `code` estable: 400 VALIDATION_ERROR/MALFORMED_REQUEST con lista de errores, 409 IDEMPOTENCY_KEY_CONFLICT, 422 regla, 500 INTERNAL_ERROR sin detalles; `@Digits(17,2)` → 400.
- `GET /transactions`: filtros `accountId`/`status`/`type`, `page` base 0, `limit` 1–100 (default 20), orden `createdAt desc, id desc`, paginación en BD; forma `content/page/limit/totalElements/totalPages`.
- OpenAPI con respuestas documentadas (springdoc) fijadas por `OpenApiIT`; GET `produces=application/json`.
- Review CHANGES_REQUESTED (E7 OpenAPI) → vuelta 2 → APPROVED 26/26. Para f7: README con `count(*)` por página y paginación keyset como mejora.

## 2026-10-28 — feature 6 integration

- `TransactionFlowIT` (18) + `TransactionTimeoutIT` (1): HTTP real (RANDOM_PORT + RestClient) → reglas → WireMock → Postgres Testcontainers, sin mocks internos. Aserciones sobre BD y WireMock, no solo respuestas.
- Concurrencia: 10 requests con la misma llave → un 201, nueve 200 con el mismo id, 1 fila, 1 llamada al proveedor. Pool Hikari de 2: 10 requests dentro del proveedor a la vez con 0 conexiones activas (falla si se agrega `@Transactional`, verificado por mutación).
- Determinismo: gate en WireMock + Awaitility, sin sleeps; timeout en IT propio (read-timeout 1s), flujo con 10s; orden esperado tomado de Postgres.
- Infra de tests: un solo Postgres compartido (`JdbcConnectionDetails`, arranque perezoso), `DatabaseCleaner` común, helpers `ProviderGate`/`ProviderStubs`/`TransactionApi`. `src/main` sin cambios.
- Review APPROVED → `/code-review` (9 hallazgos: 7 corregidos, 2 aceptados) → APPROVED vuelta 2.

## 2026-10-28 — feature 7 delivery

- Dockerfile multi-stage (build con `./mvnw`, capas de Spring Boot, runtime JRE 21, usuario no root `spring` uid 10001) y `.dockerignore`.
- Compose completo: app + postgres + provider-mock con healthchecks; `/actuator/health` UP.
- Stubs WireMock por `accountId`: aprobado (default), `acc-insufficient-funds` (422 literal), `acc-provider-down` (503 sin cuerpo), `acc-slow` (5 s > read-timeout 3 s → UNKNOWN).
- README: API primero (levantar, curl por escenario verificado contra el stack real, estados, arquitectura, PostgreSQL, escalabilidad, limitaciones) y "Uso de IA" con el arnés y el origen de la configuración base. ADRs 0002–0008.
- Review CHANGES_REQUESTED (3 frases del README: volúmenes, honestidad RT04, alcance de Sonar) → vuelta 2 → APPROVED 16/16.
- Pendiente fuera de features: override de logback 1.6.6 decidido en `docs/sonar-decisiones.md` (no versionado) y no aplicado; el usuario decidió no configurarlo.
