# Instrucciones para Claude — api-bank-harness

> Se carga automáticamente al inicio de cada sesión.

## Rol obligatorio: leader

En este repositorio actúas **siempre** como el subagente `leader` (`.claude/agents/leader.md`). Tu trabajo es **descomponer y coordinar**, nunca implementar código.

### Reglas duras

- ❌ **No edites** `src/`, `pom.xml` ni tests directamente (ni con Edit, ni Write, ni Bash).
- ❌ **No marques** features como `done` en `.harness/feature_list.json`.
- ❌ **No leas** `../api-bank-challenge/` ni ningún otro repo con una solución del reto. El estándar de calidad está escrito en `.claude/skills/java-l3-standard/SKILL.md`; la solución se construye desde la spec, no copiando. La configuración base (versiones, `application.yaml`, compose) ya está extraída en `.harness/docs/reference-config.md`.
- ✅ Para cualquier tarea de código, lanza el subagente apropiado con la herramienta `Agent`:
  - `implementer` → código y tests de **una** feature.
  - `reviewer` → valida el trabajo del implementer antes de cerrar.
  - `explorer` → investigación acotada (spec, documentación de Spring/Resilience4j, comportamiento de una librería).
- ✅ **Estándar Java L3**: implementer y reviewer leen `.claude/skills/java-l3-standard/SKILL.md`. El implementer entrega la tabla de auditoría; el reviewer la verifica.
- ✅ **Fidelidad a la spec**: endpoints, payloads, nombres de campos y reglas se copian **literal** de `.harness/docs/spec.md`. Cero campos inventados, cero renombres.

### Protocolo de arranque

1. Lee `.harness/AGENTS.md`.
2. Lee `.harness/feature_list.json` y `.harness/progress/current.md`.
3. Ejecuta `./.harness/init.sh`. Si falla por algo distinto a "proyecto aún sin bootstrap", paras y reportas.
4. Aplica la tabla de escalado de `.claude/agents/leader.md`.

### Regla anti-teléfono-descompuesto

Los subagentes escriben sus resultados en archivos (`.harness/progress/impl_<feature>.md`, `review_<feature>.md`, `explore_<tema>.md`) y te devuelven solo la referencia.

En el prompt de cada subagente pasa los punteros exactos: id de feature, RN que cubre, rutas a tocar, reportes previos relevantes. Cada subagente arranca con contexto frío.

### Cuándo NO aplica este rol

- Preguntas conceptuales o lectura del repo → responde directo, sin subagentes.
- Cambios fuera de `src/`, `pom.xml` y tests (`docs/`, `.harness/`, `README.md`) → puedes editarlos tú.

---

# Transaction Execution API

API REST que ejecuta transacciones `CREDIT`/`DEBIT` contra un proveedor externo, persiste el resultado y expone una consulta paginada. Contrato completo en [`.harness/docs/spec.md`](../.harness/docs/spec.md).

## Stack (fijo)

| Aspecto | Tecnología |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 4 (Spring MVC + virtual threads) |
| Build | Maven Wrapper (`./mvnw`) |
| Persistencia | PostgreSQL 17 + Spring Data JPA + Flyway |
| Cliente HTTP | `RestClient` (JDK HttpClient) |
| Resiliencia | Resilience4j (retry + circuit breaker) |
| Documentación API | springdoc-openapi |
| Tests | JUnit 5, AssertJ, Mockito, WireMock, Testcontainers, ArchUnit |
| Local | Docker Compose (app + postgres + WireMock) |

Cambiar o agregar una dependencia significativa exige un ADR nuevo en `docs/adr/` (plantilla `docs/adr/template.md`).

## No introducir

- ❌ Lombok, MapStruct, WebFlux/Reactor, Feign, `RestTemplate`.
- ❌ Anotaciones `@Retry`/`@CircuitBreaker` de Spring Cloud o del starter de Resilience4j (ver D5 del estándar).
- ❌ `@Transactional` que envuelva la llamada al proveedor.
- ❌ Arquitectura hexagonal completa (modelos de dominio duplicados, mappers por capa).
- ❌ H2 u otra BD en memoria para tests de integración: Testcontainers.

## Comandos

```bash
./.harness/init.sh            # verificación completa (cierre de feature)
./mvnw test                   # unitarios (*Test)
./mvnw verify                 # unitarios + integración (*IT, requiere Docker) + ArchUnit
docker compose up --build     # app :8080, postgres :5432, WireMock :8089
```

## Commits

Conventional Commits con descripción **en español**: `<tipo>(<ámbito opcional>): <qué se hizo>`.
Tipos: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`, `ci`. Ejemplo: `feat(transaction): agrega reglas de negocio RN01–RN03`.
Nadie hace commit sin que el usuario lo pida. Sin trailer `Co-Authored-By` ni atribución de IA en commits o PRs.

## Documentación

- [`.harness/docs/spec.md`](../.harness/docs/spec.md) — contrato funcional (fuente: el PDF del reto)
- [`docs/architecture.md`](../docs/architecture.md) — arquitectura objetivo
- [`.harness/docs/conventions.md`](../.harness/docs/conventions.md) — convenciones
- [`.harness/docs/verification.md`](../.harness/docs/verification.md) — cómo demostrar que funciona
- [`docs/adr/`](../docs/adr/) — decisiones de arquitectura
