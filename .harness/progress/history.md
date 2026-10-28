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
