# impl_bootstrap — Feature 1 `bootstrap` (RT05)

## Archivos creados

| Archivo | Contenido |
|---|---|
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Maven Wrapper 3.3.4, `only-script`, Maven 3.10.0 (generado con `maven-wrapper-plugin:3.3.4:wrapper`) |
| `pom.xml` | Boot 4.1.2, Java 21, deps de `reference-config.md` + ArchUnit 1.5.1; Surefire `**/*Test.java`, Failsafe `**/*IT.java` |
| `src/main/java/mx/gilortiz/transactions/TransactionsApplication.java` | Clase de arranque |
| `src/main/resources/application.yaml` | Virtual threads, datasource con `${VAR:default}`, `open-in-view: false`, `ddl-auto: validate`, actuator health/info |
| `src/main/resources/db/migration/V1__create_transactions.sql` | Tabla `transactions`, UNIQUE `idempotency_key`, CHECK de `type`/`status`, índices RN07 |
| `docker-compose.yml` | `postgres` (17-alpine, :5432, healthcheck) + `provider-mock` (WireMock 3.13.0, :8089, volumen `./wiremock`) |
| `wiremock/mappings/.gitkeep`, `wiremock/__files/.gitkeep` | Directorio montable, sin stubs (feature 7) |
| `src/test/java/mx/gilortiz/transactions/architecture/ArchitectureTest.java` | ArchUnit (Surefire): dominio no depende de `provider.http`; sin `@Autowired` en campos; + 2 tests que prueban que cada regla detecta su violación |
| `src/test/java/mx/gilortiz/transactions/architecture/fixture/**` | 3 fixtures que violan las reglas a propósito (solo test; excluidas del análisis de producción) |
| `src/test/java/mx/gilortiz/transactions/ApplicationContextIT.java` | Testcontainers Postgres 17 + `@ServiceConnection`: contexto arranca, Flyway en V1, UNIQUE e índices existen |

Modificado: `.harness/progress/current.md` (plan del implementer).

## Dependencias

Todas de `reference-config.md` / stack de `.claude/CLAUDE.md`. Agregada ArchUnit `com.tngtech.archunit:archunit-junit5:1.5.1` (última release en Maven Central; ArchUnit ya está en la tabla de stack, sin ADR). `maven-failsafe-plugin` versión del parent de Boot. Sin Lombok, MapStruct, WebFlux, Feign, RestTemplate, starter de Resilience4j ni H2.

## Verificación

`./.harness/init.sh` → `EXIT=0` ("verify verde (unitarios + integración + ArchUnit)", "Entorno listo.").

`./mvnw verify`:
```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.521 s -- in mx.gilortiz.transactions.architecture.ArchitectureTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0          <- surefire
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.379 s -- in mx.gilortiz.transactions.ApplicationContextIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0          <- failsafe
[INFO] BUILD SUCCESS
```
Separación: `target/surefire-reports` solo contiene `ArchitectureTest`; `target/failsafe-reports` solo `ApplicationContextIT`.

### docker compose

`docker compose up -d --wait` con los puertos del archivo **falló por puerto ocupado**: `5432` y `8089` los tienen los contenedores `api-bank-challenge-postgres-1` y `api-bank-challenge-provider-mock-1` de otro proyecto (no los detuve: no son de este repo). Para demostrar que los servicios levantan, corrí el mismo compose con un override temporal de puertos (en el scratchpad, no en el repo; `ports: !override` 15432/18089):
```
postgres       Up 5 seconds (healthy)   0.0.0.0:15432->5432/tcp
provider-mock  Up 5 seconds (healthy)   0.0.0.0:18089->8080/tcp
curl -s localhost:18089/__admin/mappings  -> {"mappings":[],"meta":{"total":0}}
pg_isready -U transactions -d transactions -> accepting connections
```
Luego `docker compose down`. **Para que el reviewer lo verifique en :5432/:8089 hay que detener antes los contenedores de `api-bank-challenge`.**

## Decisiones no obvias

1. **Columnas `code` y `message`** (RN06): nombre literal de los campos del cuerpo de rechazo del proveedor en spec.md. El resto de columnas son el snake_case literal de los campos del response (`account_id`, `type`, `amount`, `currency`, `description`, `status`, `provider_transaction_id`, `balance_after`, `created_at`) + `id` UUID e `idempotency_key VARCHAR(100)` (máx. 100 de feature 5). Si feature 4 prefiere otro nombre, se cambia con V2 (aún no hay entidad).
2. **Dinero `NUMERIC(19,2)`**; `currency VARCHAR(3)` (no `CHAR`) para que `ddl-auto: validate` empate con `String` sin `columnDefinition`.
3. **CHECK de `type` y `status`** con los valores de spec/architecture (`PENDING, EXECUTED, REJECTED, FAILED, UNKNOWN`): la BD rechaza estados inválidos.
4. **Índices RN07** con sufijo `(created_at, id)` = orden estable de paginación (C6): `(account_id, …)`, `(status, …)`, `(type, …)` y `(created_at, id)` para la consulta sin filtros. `idempotency_key` nulo permite muchas filas sin llave (semántica de UNIQUE en Postgres).
5. **Sin bloque `provider.*` en `application.yaml`**: entra en feature 3 junto con su `@ConfigurationProperties` record; dejarlo ahora sería configuración muerta.
6. **ArchUnit con `allowEmptyShould(true)`** y patrón `..transaction..` (segmento exacto; no captura `transactions`). Para que la regla no pase en vacío, `domainRuleDetectsViolation` y `fieldInjectionRuleDetectsViolation` evalúan las mismas `ArchRule` contra fixtures que violan y afirman `hasViolation()` + la clase culpable. El análisis de producción usa `DO_NOT_INCLUDE_TESTS`, así las fixtures no contaminan.
7. **IT con inyección por constructor** (`@Autowired` en el constructor del test, no en campos).

## Auditoría Java L3

| Regla | Estado | Evidencia (`archivo:línea`) | Nota |
|-------|--------|------------------------------|------|
| A1 | ✅ | `TransactionsApplication.java:1` (raíz `mx.gilortiz.transactions`) | Paquetes por funcionalidad llegan en features 2–5 |
| A2 | N/A | — | Sin controller/service aún |
| A3 | N/A | — | Puerto en feature 3 |
| A4 | ✅ | `ArchitectureTest.java:24-27`, `:39`, `:51` | Regla ejecutable + prueba de que detecta la violación |
| A5–A7 | N/A | — | Features 3–5 |
| B1–B5 | N/A | — | Dominio en features 2 y 4 |
| C1 | ✅ | `V1__create_transactions.sql:1`, `application.yaml:13`; `ApplicationContextIT.java:36` | Flyway V1 aplicada y validate activo |
| C2 | N/A | — | Feature 4 |
| C3 | ✅ (parcial) | `application.yaml:11` | `open-in-view: false`; la regla `@Transactional` va en feature 4 |
| C4 | ✅ (esquema) | `V1__create_transactions.sql:18`; `ApplicationContextIT.java:43` | UNIQUE en BD; lógica en feature 4 |
| C5 | N/A | — | Feature 4 |
| C6 | ✅ (índices) | `V1__create_transactions.sql:22-25`; `ApplicationContextIT.java:57` | Orden estable en feature 5 |
| C7 | ✅ (esquema) | `V1__create_transactions.sql:2` | `id UUID`; generación v7 en feature 4 |
| D1–D5 | N/A | — | Feature 3 |
| E1–E7 | N/A | — | Feature 5 (springdoc ya en el pom) |
| F1 | ✅ | `application.yaml:3`, `:9` | Virtual threads + `DB_POOL_SIZE` |
| F2 | ✅ (parcial) | `application.yaml:5-9` | `${VAR:default}`; record de `provider.*` en feature 3 |
| F3 | N/A | — | Sin logs propios |
| G1 | ✅ | `pom.xml:136-140` | Surefire solo `*Test` |
| G4 | ✅ (base) | `pom.xml:145-157`; `ApplicationContextIT.java:23` | Failsafe solo `*IT`; Testcontainers Postgres real |
| G5 | ✅ | `ArchitectureTest.java:24-31` | A4 + sin `@Autowired` en campos |
| G7 | ✅ | `ArchitectureTest.java:38-64`, `ApplicationContextIT.java:35-56` | `@DisplayName` en español; afirman versión "1", columna UNIQUE exacta, índices, violaciones |
| H1 | N/A | — | Sin tipos de dominio aún |
| H2 | ✅ | `ArchitectureTest.java:29-31`; `ApplicationContextIT.java:28-32` | Regla + constructor injection en el IT |
| H3 | ✅ | `pom.xml` | Sin Lombok |
| H4 | ✅ | `V1__create_transactions.sql:11,21`, `application.yaml:8,12`, `ArchitectureTest.java:23` | Solo comentarios de *por qué* |
| H5 | ✅ | — | Sin `System.out`, TODOs ni código muerto |

## Pre-matriz de reglas

| Regla | Qué exige | Implementación (`archivo:línea`) | Test que la afirma (`archivo:línea`) | Verificación manual |
|-------|-----------|----------------------------------|--------------------------------------|---------------------|
| RT05 | Código en un repositorio Git | Repo git existente; archivos nuevos sin trackear (no hago commit por instrucción) | N/A (no testeable por código) | `git status --short` muestra `pom.xml`, `src/`, `mvnw`, `.mvn/`, `docker-compose.yml`, `wiremock/` como `??` listos para commit |
| ACC-verify | `./mvnw verify` BUILD SUCCESS | `pom.xml` | 4 Test + 3 IT verdes | `./mvnw verify` → `BUILD SUCCESS` |
| ACC-stack | Solo deps del stack | `pom.xml:28-125` | — | Revisión manual del pom |
| ACC-surefire/failsafe | `*Test` en Surefire, `*IT` en Failsafe | `pom.xml:136-157` | reportes separados | `ls target/{surefire,failsafe}-reports` |
| ACC-compose | postgres :5432 y WireMock :8089 | `docker-compose.yml` | — | Ver sección compose (verificado en puertos alternos por conflicto externo) |
| ACC-V1 | UNIQUE `idempotency_key` + índices RN07 | `V1__create_transactions.sql:18,22-25` | `ApplicationContextIT.java:43`, `:57` | — |
| ACC-yaml | virtual threads, OIV false, validate, `${VAR:default}` | `application.yaml:3,5-9,11,13` | `ApplicationContextIT.java:36` (arranca con validate) | — |
| ACC-archunit | dominio ↛ `provider.http`; sin `@Autowired` en campos | `ArchitectureTest.java:24-31` | `ArchitectureTest.java:39,45,51,64` | — |
| ACC-IT | Contexto arranca contra Postgres real | `ApplicationContextIT.java:17-23` | `ApplicationContextIT.java:36` | — |

## Commit sugerido

```
build: bootstrap del proyecto Spring Boot 4 con Postgres, Flyway, WireMock y ArchUnit
```
