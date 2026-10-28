# Bitácora

> Append-only. Una entrada por feature cerrada: `## YYYY-MM-DD — feature <id> <name>` + 3–5 bullets.

## 2026-10-28 — feature 1 bootstrap

- Spring Boot 4.1.2 / Java 21 con Maven Wrapper; versiones base en `.harness/docs/reference-config.md`.
- Flyway V1 crea `transactions` con UNIQUE en `idempotency_key` e índices para filtros RN07.
- Surefire (`*Test`) y Failsafe (`*IT`) separados; ArchUnit (dominio ↛ `provider.http`, sin `@Autowired` en campos) + IT de contexto con Testcontainers.
- docker compose: postgres :5432 y WireMock :8089. Bloque `provider.*` diferido a feature 3.
- Review APPROVED en 1 vuelta. Pendiente para feature 3/4: regla ArchUnit "sin `@Transactional` alrededor del proveedor".
