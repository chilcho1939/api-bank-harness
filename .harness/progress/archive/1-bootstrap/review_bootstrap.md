# Review — feature 1 bootstrap

**Veredicto:** APPROVED

**Cobertura:** 9/9 reglas y criterios de aceptación verificados (RT05 + 8 criterios de aceptación).

## Verificación ejecutable (propia)

- `./mvnw clean verify`:
  - Surefire: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in mx.gilortiz.transactions.architecture.ArchitectureTest`
  - Failsafe: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in mx.gilortiz.transactions.ApplicationContextIT`
  - `BUILD SUCCESS`
- `./.harness/init.sh` → `verify verde`, `Entorno listo.`, `EXIT=0`.
- Compose: los puertos 5432/8089 los ocupan `api-bank-challenge-postgres-1` y `api-bank-challenge-provider-mock-1` (otro proyecto; no se tocaron). Validé con `docker compose config` (publica `5432` y `8089`) y lo levanté con `-p bootstrap-review` y un override de puertos en el scratchpad (`ports: !override` 25432/28089). Luego `docker compose -p bootstrap-review down`, sin `-v`.

## Matriz de reglas

| Regla | Qué exige | Cómo lo probé | Evidencia (salida) | Resultado |
|-------|-----------|---------------|--------------------|-----------|
| RT05 | Código en un repositorio Git | `git status --short` | rama `main`; `pom.xml`, `src/**`, `mvnw`, `.mvn/`, `docker-compose.yml`, `wiremock/` aparecen como `??`, listos para commit. No hay basura (`target/` ignorado por `.gitignore`) | PASS |
| ACC-1 | `./mvnw verify` → BUILD SUCCESS | `./mvnw clean verify` | 4 Test + 3 IT, 0 fallos, `BUILD SUCCESS` | PASS |
| ACC-2 | pom solo con dependencias del stack | lectura de `pom.xml:28-125` + `dependency:tree` filtrado por lombok, mapstruct, webflux, reactor-core, feign, h2, hsqldb, resilience4j-spring y spring-cloud | `sin dependencias prohibidas`. Resilience4j solo el núcleo (`pom.xml:52-60`); ArchUnit está en la tabla de stack de CLAUDE.md, así que no hace falta ADR | PASS |
| ACC-3 | Surefire corre `*Test`, Failsafe corre `*IT` | `pom.xml:136-140` y `:145-157`; `ls target/{surefire,failsafe}-reports` | surefire-reports solo tiene `ArchitectureTest`; failsafe-reports solo tiene `ApplicationContextIT` | PASS |
| ACC-4 | compose levanta postgres :5432 y WireMock :8089 | `docker compose config`; `up -d --wait` con puertos alternos; `curl :28089/__admin/mappings`; `pg_isready` | config: `published: "5432"`→5432 y `published: "8089"`→8080. `bootstrap-review-postgres-1 Up (healthy)`, `bootstrap-review-provider-mock-1 Up (healthy)`; WireMock responde `200 {"mappings":[],"meta":{"total":0}}`; `accepting connections` | PASS (en :5432/:8089 literales no se probó por el conflicto externo; el mapeo del archivo se verificó con `config`) |
| ACC-5 | V1 crea `transactions` con UNIQUE en `idempotency_key` e índices para RN07 | test `ApplicationContextIT.java:43-53` (`containsExactly("idempotency_key")`) y `:57-66` (cuatro índices); `\d transactions` en el Postgres de compose después de que la app migró; INSERT duplicado | `uk_transactions_idempotency_key UNIQUE CONSTRAINT`; `ix_..._account_id_created_at_id`, `ix_..._status_...`, `ix_..._type_...`, `ix_..._created_at_id`; el INSERT duplicado falla con `ERROR: duplicate key value violates unique constraint "uk_transactions_idempotency_key"` | PASS |
| ACC-6 | yaml: virtual threads, open-in-view false, ddl-auto validate, `${VAR:default}` | `application.yaml:3`, `:11`, `:13`, `:5-9`; app empaquetada levantada con `DB_URL=...:25432` | `Successfully applied 1 migration ... now at version v1`, `Started TransactionsApplication`, `/actuator/health` → `{"status":"UP"}`. Además, la override de `DB_URL` funciona y `validate` no rompe | PASS |
| ACC-7 | ArchUnit: el dominio no depende de `provider.http`; sin campos `@Autowired` | `ArchitectureTest.java:24-27`, `:29-31`, `:39-47`; pruebas de detección `:51-60` y `:64-72` | Las dos reglas pasan sobre producción (`DO_NOT_INCLUDE_TESTS`, `:33-35`) y **fallan sobre las fixtures que las violan** (`hasViolation()` y la clase culpable). `allowEmptyShould(true)` está justificado porque las fixtures demuestran que la regla no pasa en vacío | PASS |
| ACC-8 | Un `*IT` con Testcontainers arranca el contexto contra Postgres real | `ApplicationContextIT.java:17-23` (`@SpringBootTest`, `@Testcontainers`, `postgres:17-alpine`, `@ServiceConnection`), `:36-39` | Afirma que Flyway está en la versión `"1"` y que `count(*)` es 0 (tabla real); 3/3 verdes | PASS |

### Decisiones del implementer

- **Columnas `code` y `message` (RN06):** spec.md, RN06, dice literalmente "se persiste con su `code` y `message`". Coincide también con el cuerpo de falla del proveedor. Es fiel a la spec, sin renombres. **Aceptado.**
- **Bloque `provider.*` diferido a feature 3:** ningún criterio de aceptación de feature 1 lo exige. Agregarlo sin `@ConfigurationProperties` sería configuración muerta (H5). **Aceptado.**
- **Commit sugerido** `build: bootstrap del proyecto Spring Boot 4 con Postgres, Flyway, WireMock y ArchUnit`: tipo válido (`build`), descripción en español y una sola línea. **Cumple Conventional Commits.**

## Estándar Java L3 (filas verificadas por mí)

| Regla | Evidencia | Resultado |
|-------|-----------|-----------|
| A4 / G5 | `ArchitectureTest.java:24-27`; el patrón `..transaction..` exige el segmento exacto. `mx.gilortiz.transactions.provider.http` no coincide (segmento `transactions`). La regla detecta la violación en la fixture (`:51-60`, verde) | PASS |
| C1 | `application.yaml:13` `ddl-auto: validate`; `V1__create_transactions.sql`. La app real arrancó y validó contra el esquema migrado (log `now at version v1` + `Started`) | PASS |
| C3 (parcial) | `application.yaml:11` `open-in-view: false`. No existe código de proveedor todavía | PASS |
| C4 (esquema) | `V1__create_transactions.sql:18`; el INSERT duplicado en Postgres fue rechazado | PASS |
| C6 (índices) | `V1__create_transactions.sql:22-25`; `\d transactions` muestra los 4 índices | PASS |
| F1 | `application.yaml:3` `spring.threads.virtual.enabled: true`; `:9` `hikari.maximum-pool-size: ${DB_POOL_SIZE:20}` | PASS |
| F2 (parcial) | `application.yaml:5-9`, todo con `${VAR:default}` | PASS |
| G1 / G4 | `pom.xml:136-157`; los reportes están separados por plugin | PASS |
| H2 | `ApplicationContextIT.java:25-32` usa constructor y campos `final`; `@Autowired` solo en el constructor | PASS |
| H3 / H5 | `grep -rnE "System\.out|printStackTrace|TODO" src` → sin resultados; sin Lombok en el árbol | PASS |
| H4 | Comentarios solo de *por qué*: `application.yaml:8,12`, `V1:11,21`, `ArchitectureTest.java:23` | PASS |

La tabla de auditoría del implementer coincide con lo que observé. Las filas N/A (A2, A3, A5-A7, B*, C2, C5, D*, E*, F3) están bien justificadas: corresponden a features 2-5.

## Checkpoints

- C1: [x] están todos los archivos del arnés y los 4 agentes más el SKILL; `init.sh` termina con EXIT=0.
- C2: [x] hay una sola feature `in_progress` (la 1) y ninguna `done`; `current.md` describe la sesión activa. Falta la entrada de `history.md`, que se escribe al cerrar (le toca al leader).
- C3: [x] paquete raíz `mx.gilortiz.transactions`; ArchUnit pasa; sin dependencias prohibidas; sin `System.out` ni `@Autowired` en campos; ArchUnit ya está en la tabla de stack, sin ADR nuevo. La regla ArchUnit "ningún `@Transactional` envuelve al proveedor" no aplica todavía porque no hay servicio ni proveedor (ver Notas).
- C4: [x] 7 tests, ninguno `@Disabled`, y todos afirman resultados concretos. Bordes y WireMock: N/A en feature 1.
- C5: pendiente de cierre por el leader: entrada en `history.md`, marcar la feature como `done` y archivar `impl_bootstrap.md` y este review. No hay archivos sin trackear sospechosos.

## Cambios requeridos

Ninguno.

## Notas (no bloqueantes)

1. Para la feature 4 o la 3: ampliar `ArchitectureTest` con la regla del CHECKPOINT C3 "ningún `@Transactional` envuelve la llamada al proveedor" (por ejemplo, ninguna clase que dependa del puerto lleva `@Transactional` en el método que lo invoca).
2. `docker compose down` sin `-v` deja el volumen anónimo que declara la imagen `postgres:17-alpine` del proyecto `bootstrap-review`. Es inocuo y no es de otro proyecto.
3. Mientras sigan corriendo los contenedores `api-bank-challenge-*`, `docker compose up` de este repo en :5432/:8089 va a fallar. Conviene anotarlo en el README (feature 7) o parametrizar los puertos con `${POSTGRES_PORT:-5432}`.
