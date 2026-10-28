# Implementación: feature 7 `delivery` (RT01, RT03, RT04)

Estoy en la rama `feat/7-delivery`. No hice commits. La feature sigue `in_progress`.

## Archivos

| Archivo | Cambio |
|---|---|
| `Dockerfile` (nuevo) | Imagen multi-stage. **builder** (`eclipse-temurin:21-jdk`): `./mvnw dependency:go-offline` en una capa propia, seguido de `package -DskipTests -Djacoco.skip=true`. Ambos pasos usan caché BuildKit de `~/.m2`. Después corre `java -Djarmode=tools -jar application.jar extract --layers --destination extracted`. **runtime** (`eclipse-temurin:21-jre`): usuario de sistema `spring` (uid/gid 10001, sin shell) y 4 `COPY`, uno por capa (`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`). Lleva `USER spring:spring`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` y `ENTRYPOINT java -jar application.jar` |
| `.dockerignore` (nuevo) | Excluye `target/`, `.git/`, `.github/`, `.idea/`, `.vscode/`, `*.iml`, `.DS_Store`, `.claude/`, `.harness/`, `docs/`, `wiremock/`, `docker-compose.yml`, `Dockerfile`, `.dockerignore` y `README.md` |
| `docker-compose.yml` | Nuevo servicio `app`: `build: .`, `8080:8080`. Variables `DB_URL=jdbc:postgresql://postgres:5432/transactions`, `DB_USER`, `DB_PASSWORD` y `PROVIDER_BASE_URL=http://provider-mock:8080`. `depends_on` espera a `postgres` `service_healthy` y a `provider-mock` `service_started`. El healthcheck usa `curl` contra `/actuator/health` y exige `"status":"UP"`. `postgres` y `provider-mock` no cambian |
| `wiremock/mappings/01-approved-default.json` (nuevo) | Prioridad 10, cualquier `POST /provider/v1/execute` → 200 con `transactionId` (`txn-` más 12 caracteres al azar, vía response-template), `status: APPROVED`, `balance: 5500.00` y `executedAt: {{now}}` |
| `wiremock/mappings/02-rejected-insufficient-funds.json` (nuevo) | Prioridad 1, `$.accountId == acc-insufficient-funds` → 422 con el cuerpo literal de la spec (`REJECTED` / `INSUFFICIENT_FUNDS` / mensaje) |
| `wiremock/mappings/03-unavailable-503.json` (nuevo) | Prioridad 1, `acc-provider-down` → 503 sin cuerpo |
| `wiremock/mappings/04-slow-response.json` (nuevo) | Prioridad 1, `acc-slow` → 200 aprobado con `fixedDelayMilliseconds: 5000`, mayor que el `read-timeout` de 3 s |
| `wiremock/mappings/.gitkeep` | Eliminado: la carpeta ya tiene contenido |
| `README.md` | Reescrito. Secciones: cómo levantar, configuración, escenarios con `curl` (salida real), API, estados, arquitectura, persistencia (RT01), escalabilidad, limitaciones y mejoras, tests, índice de ADRs y **Uso de IA** (RT04: el arnés, roles, estándar L3, ArchUnit, `/code-review`, CI con Sonar, trazabilidad y reparto IA/persona) |
| `docs/adr/0002-paquete-por-funcionalidad-y-puerto-del-proveedor.md` | Capas e inversión de dependencias |
| `docs/adr/0003-spring-mvc-con-virtual-threads.md` | MVC + virtual threads |
| `docs/adr/0004-postgresql-y-modelo-de-datos.md` | PostgreSQL y modelo |
| `docs/adr/0005-estados-y-resultado-incierto.md` | Estados, `PENDING` previo y `UNKNOWN` |
| `docs/adr/0006-resiliencia-del-cliente-del-proveedor.md` | Resiliencia; incluye el starter `spring-boot-starter-restclient` que f3 dejó sin ADR |
| `docs/adr/0007-idempotencia-por-idempotency-key.md` | Idempotencia |
| `docs/adr/0008-contrato-de-errores-rfc-9457.md` | Contrato de errores |
| `.harness/progress/current.md` | Plan del implementer |

- No toqué `src/`, `pom.xml` ni `docs/sonar-decisiones.md`.
- Dependencias nuevas: ninguna. Las imágenes base `eclipse-temurin:21-jdk/jre` son de runtime, no del stack Maven.
- Los ADRs salen de los reportes archivados (`archive/1..6`) y de `history.md`. Cada decisión y cada alternativa descartada aparece en esos reportes; no hay nada inventado.

## Verificación

### `./.harness/init.sh`

`EXIT=0`. Muestra `[OK] verify verde (unitarios + integración + ArchUnit)` y `[OK] Entorno listo.`

```
surefire: Tests run: 192, Failures: 0, Errors: 0, Skipped: 0
failsafe: Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
```

Las líneas `ERROR`/`WARN duplicate key` del log son las esperadas de tests anteriores (ver f3, f4 y f6).

### `docker compose up --build -d`

```
Image api-bank-harness-app Built
Container api-bank-harness-postgres-1 Healthy
Container api-bank-harness-app-1 Started

NAME                               STATUS                    PORTS
api-bank-harness-app-1             Up 6 seconds (healthy)    0.0.0.0:8080->8080/tcp
api-bank-harness-postgres-1        Up 12 seconds (healthy)   0.0.0.0:5432->5432/tcp
api-bank-harness-provider-mock-1   Up 12 seconds (healthy)   0.0.0.0:8089->8080/tcp

$ curl -s localhost:8080/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}

$ docker compose exec app id
uid=10001(spring) gid=10001(spring) groups=10001(spring)

app log: Flyway "Successfully applied 1 migration ... now at version v1"; "Started TransactionsApplication in 2.134 seconds"
```

Capas de la imagen (`docker history`). La de aplicación pesa 73.7 kB, así que un cambio de código solo reconstruye esa:

```
73.7kB  COPY /build/extracted/application/ ./
4.1kB   COPY /build/extracted/snapshot-dependencies/
4.1kB   COPY /build/extracted/spring-boot-loader/ ./
62.1MB  COPY /build/extracted/dependencies/ ./
User=spring:spring  Entrypoint=[java -jar application.jar]
```

### `curl` de cada escenario (los mismos que en el README)

| # | Escenario | Request | Resultado |
|---|---|---|---|
| 1 | Aprobado (default) | `acc-123456` CREDIT 1500.00 | **201** `status: EXECUTED`, `providerTransactionId: txn-ne8ewckjzhzm`, `balanceAfter: 5500.00` (0.18 s) |
| 2 | Rechazo por fondos | `acc-insufficient-funds` DEBIT 2500.00 | **201** `status: REJECTED`, `providerTransactionId: null`, `balanceAfter: null` |
| 3 | 503 sin cuerpo | `acc-provider-down` DEBIT 100.00 | **201** `status: FAILED` |
| 4 | Lento | `acc-slow` CREDIT 300.00 | **201** `status: UNKNOWN` en **3.02 s**, sin reintento |
| 5a | RN01 | CREDIT `1.00` | **422** `code: AMOUNT_TOO_LOW`, `detail: "El monto debe ser mayor a 1.00"` |
| 5b | RN02 | DEBIT `10000.01` | **422** `code: DEBIT_AMOUNT_EXCEEDS_LIMIT` |
| 5c | RN03 | `USD` | **422** `code: UNSUPPORTED_CURRENCY` |
| 5d | Formato | `1500.005` | **400** `VALIDATION_ERROR`, `errors: [{field: amount, ...}]` |
| 6 | Idempotencia, primera | `Idempotency-Key: pedido-42`, `acc-777` DEBIT **10000.00** | **201** EXECUTED, id `01a18481-0865-…`. También confirma el borde de RN02: 10000.00 se acepta |
| 6 | Replay | mismo comando | **200**, el mismo id y el mismo cuerpo |
| 7 | Conflicto | `pedido-42` con 9000.00 | **409** `IDEMPOTENCY_KEY_CONFLICT` |
| 8a | GET con filtros | `?accountId=acc-777&status=EXECUTED&type=DEBIT` | **200**, 1 elemento, `totalElements: 1` |
| 8b | GET por estado | `?status=UNKNOWN` | **200**, solo `acc-slow` |
| 8c | GET paginado | `?page=1&limit=2` | **200** `{page: 1, limit: 2, totalElements: 5, totalPages: 3}`, contenido `[acc-provider-down, acc-insufficient-funds]` (orden desc) |
| 8d | GET con `limit` inválido | `?limit=101` | **400** `VALIDATION_ERROR`, `field: limit` |

Lo que quedó persistido (`psql`):

```
       account_id       |  status  |        code        |            message (60)                                  | provider_transaction_id | idempotency_key
 acc-123456             | EXECUTED |                    |                                                          | txn-ne8ewckjzhzm        |
 acc-insufficient-funds | REJECTED | INSUFFICIENT_FUNDS | The account does not have enough balance to complete the tra |                   |
 acc-provider-down      | FAILED   | PROVIDER_ERROR     | El proveedor respondió HTTP 503 sin cuerpo               |                         |
 acc-slow               | UNKNOWN  | PROVIDER_TIMEOUT   | El proveedor no respondió a tiempo; resultado incierto   |                         |
 acc-777                | EXECUTED |                    |                                                          | txn-ca12ytk7vmv9        | pedido-42
(5 rows)
```

Journal de WireMock (`GET :8089/__admin/requests`): **5 llamadas**, todas con el cuerpo literal `{accountId, type, amount, currency}`. Los 422, el 400, el replay y el 409 **no** llegaron al proveedor (RN04).

```
{"accountId":"acc-777","type":"DEBIT","amount":10000.00,"currency":"MXN"} -> 200
{"accountId":"acc-slow","type":"CREDIT","amount":300.00,"currency":"MXN"} -> 200 (con retraso)
{"accountId":"acc-provider-down","type":"DEBIT","amount":100.00,"currency":"MXN"} -> 503
{"accountId":"acc-insufficient-funds","type":"DEBIT","amount":2500.00,"currency":"MXN"} -> 422
{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN"} -> 200
```

Al final corrí `docker compose down`, sin `-v`. Contenedores y red quedaron eliminados.

## Decisiones no obvias

1. **Los tests no corren dentro del `docker build`.** Los `*IT` necesitan Docker (Testcontainers), y lo que se usa en un build de imagen es `-DskipTests -Djacoco.skip=true`. La suite completa la corren `init.sh` y CI. Así queda escrito en el comentario del Dockerfile.
2. **Las capas se sacan con `-Djarmode=tools extract --layers`**, el mecanismo de Boot 3.3+/4, en lugar del `layertools` obsoleto. El jar se copia antes como `application.jar`, así que el `ENTRYPOINT` no depende de la versión del artefacto. El jar extraído lleva el classpath en su manifiesto, por eso basta `java -jar application.jar` sin `JarLauncher`.
3. **El healthcheck usa `curl`.** Comprobé que `eclipse-temurin:21-jre` (Ubuntu) trae `/usr/bin/curl` y `/usr/bin/wget`. El check exige `"status":"UP"` en el cuerpo, no solo HTTP 200.
4. **Usuario no root con uid fijo 10001**, distinto del `ubuntu` (1000) que trae la base, de sistema y sin shell. No reutilicé `ubuntu` porque pertenece a `sudo` y `adm`.
5. **`depends_on` de `provider-mock` es `service_started`.** El proveedor no hace falta para arrancar ni para el health. Si no está, el cliente lo resuelve como `FAILED`.
6. **Elección de los stubs:**
   - El rechazo responde **422**: la spec acepta cualquier 4xx–5xx con cuerpo, y 422 es el más natural para un rechazo de negocio.
   - El stub lento es un 200 **aprobado** con retraso. Así se demuestra que, aunque el proveedor sí aprobó, el cliente que hizo timeout registra `UNKNOWN`: el caso que justifica ese estado.
   - Los `accountId` son descriptivos (`acc-insufficient-funds`, `acc-provider-down`, `acc-slow`) y están documentados en la tabla del README.
7. **El stub aprobado genera un `transactionId` distinto en cada llamada** (response-template de WireMock 3). Con uno fijo, todas las transacciones aprobadas tendrían el mismo `providerTransactionId`, algo irreal y confuso al conciliar. `balance` queda fijo en 5500.00 (el ejemplo de la spec): el mock no lleva saldos, que es justo lo que está fuera de alcance.
8. **Advertencia del circuit breaker en el README.** Si se repiten muchas veces los escenarios 503 o lento (10 o más llamadas y al menos 50 % de fallas), el circuito se abre 30 s y las siguientes transacciones quedan `FAILED`/`PROVIDER_CIRCUIT_OPEN`. Es el comportamiento diseñado; lo documenté para que quien pruebe no lo tome por un bug.
9. **El ADR-0006 cubre `spring-boot-starter-restclient`**, el starter que f3 agregó sin ADR y dejó pendiente para el leader o para f7.
10. **Lo pendiente de `history.md` está en el README** (sección Limitaciones y mejoras):
    - `count(*)` por página y keyset como mejora.
    - `code`/`message` del rechazo no expuestos (spec).
    - Conciliación de `PENDING`/`UNKNOWN` fuera de alcance.
    - `FAILED` consume la llave.
    - Duplicado en vuelo → 200 `PENDING`.
    - `PROVIDER_BASE_URL` en compose (aplicado).
11. **En "Uso de IA" no atribuyo decisiones a la persona** más allá de definir el arnés, el estándar y el stack, y de revisar y mergear los PRs. Las decisiones fuera de la spec se tomaron en el triage del leader, según `history.md`.

## Bug de producción

Ninguno. Todos los escenarios se comportaron como especifican los ADRs y los reportes de f3 a f6.

## Auditoría Java L3 (filas aplicables)

La feature no toca `src/`. Las filas de código se auditan como "sin cambios" y solo se marcan con evidencia las que la entrega afecta.

| Regla | Estado | Evidencia (`archivo:línea`) | Nota |
|-------|--------|------------------------------|------|
| A1–A7 | N/A | — | Sin cambios de código. Se documentan en ADR-0002 y en la sección Arquitectura del README |
| B1–B5 | N/A | — | Sin cambios. B4 (`UNKNOWN`) se demuestra en compose: escenario 4 → `UNKNOWN`/`PROVIDER_TIMEOUT` |
| C1 | ✅ | log de `app` en compose: Flyway aplica V1 y Hibernate valida | Contra un Postgres vacío del compose |
| C2/C3 | N/A | — | Sin cambios. ADR-0005 los documenta; ArchUnit sigue en verde en `init.sh` |
| C4/C5 | ✅ (runtime) | curls 6 y 7 | 201 → 200 con el mismo id → 409; 1 llamada al proveedor |
| C6/E5/E6 | ✅ (runtime) | curls 8a–8d | Orden desc estable, forma `content/page/limit/totalElements/totalPages`, `limit=101` → 400 |
| D1 | ✅ | `docker-compose.yml:10` (`PROVIDER_BASE_URL`); escenario 4 en 3.02 s | El read-timeout configurado se respeta en el contenedor |
| D2 | ✅ (runtime) | journal de WireMock: `acc-slow` recibió 1 llamada | Sin reintento tras el timeout de lectura |
| D3–D5 | N/A | — | Sin cambios; documentado en ADR-0006 |
| E1/E2 | ✅ (runtime) | curls 5a–5d | 422 con `code` de la regla; 400 con `errors` |
| E4 | ✅ (runtime) | curls 2–4 | 201 aunque el proveedor rechace o falle |
| E7 | N/A | — | Sin cambios; README enlaza `/v3/api-docs` y `/swagger-ui.html` |
| F1 | N/A | — | Sin cambios (`application.yaml` intacto) |
| F2 | ✅ | `docker-compose.yml:6-10` | El compose solo sobreescribe `${VAR:default}` existentes; no hay propiedades nuevas |
| F3 | N/A | — | Sin cambios de logging |
| G1–G7 | ✅ | `init.sh` EXIT=0, surefire 192 / failsafe 39, 0 fallos | Sin tests nuevos: la feature no agrega código Java. La verificación de entrega es el smoke HTTP de arriba |
| H1–H4 | N/A | — | Sin código Java |
| H5 | ✅ | `git status`: sin temporales ni `System.out` | `/tmp` limpio; `wiremock/mappings/.gitkeep` eliminado porque la carpeta ya tiene contenido |

## Pre-matriz de reglas y acceptance

| Regla | Qué exige | Implementación (`archivo:línea`) | Test que la afirma | Verificación manual |
|-------|-----------|----------------------------------|--------------------|---------------------|
| RT01 | Persistencia elegida | `docker-compose.yml:25` (postgres 17), `V1__create_transactions.sql` | `ApplicationContextIT`, `TransactionPersistenceIT` (sin cambios) | `psql … select …` → 5 filas con estado y `code` |
| RT01 | **Justificada en el README** | `README.md` § "Persistencia: por qué PostgreSQL"; `docs/adr/0004-postgresql-y-modelo-de-datos.md` | — (documentación) | lectura |
| RT03 | Instrucciones para **levantar** | `README.md` § "Cómo levantarlo" (compose, health, puertos, Swagger, `spring-boot:run`, variables) | — | `docker compose up --build -d` → 3 servicios healthy, health `UP` |
| RT03 | Instrucciones para **ejecutar** | `README.md` § "Escenarios con `curl`" (8 escenarios con su salida real) y § "Tests" | — | todos los curls ejecutados (tabla de arriba) |
| RT03 | **Decisiones de diseño** | `README.md` § Arquitectura, Estados, Escalabilidad, Limitaciones e índice de ADRs; `docs/adr/0002`–`0008` | — | lectura |
| RT04 | Nota sobre el uso de IA **y de qué forma** | `README.md` § "Uso de IA" (flujo, roles, estándar, ArchUnit, `/code-review`, CI y Sonar, trazabilidad, reparto IA/persona) | — | lectura |
| ACC-1 | Dockerfile multi-stage | `Dockerfile:4` (builder), `:19` (runtime) | — | `docker compose build` OK |
| ACC-1 | Capas de Spring Boot | `Dockerfile:16` (`extract --layers`), `:26-29` | — | `docker history`: 4 `COPY` por capa (62.1 MB / 4.1 kB / 4.1 kB / 73.7 kB) |
| ACC-1 | Usuario no root | `Dockerfile:22-23`, `:31` | — | `docker compose exec app id` → `uid=10001(spring)`; `Config.User=spring:spring` |
| ACC-2 | Compose levanta app, postgres y WireMock | `docker-compose.yml:2-44` | — | `docker compose ps`: 3 servicios `healthy` |
| ACC-2 | `/actuator/health` en UP | `docker-compose.yml:16-22` (healthcheck) | — | `{"groups":["liveness","readiness"],"status":"UP"}` |
| ACC-3 | Stub aprobado (default) | `wiremock/mappings/01-approved-default.json` | — | curl 1 → 201 EXECUTED |
| ACC-3 | Stub de rechazo por fondos | `wiremock/mappings/02-rejected-insufficient-funds.json` | — | curl 2 → 201 REJECTED; BD `INSUFFICIENT_FUNDS` + mensaje literal |
| ACC-3 | Stub 503 sin cuerpo | `wiremock/mappings/03-unavailable-503.json` | — | curl 3 → 201 FAILED; BD `PROVIDER_ERROR` |
| ACC-3 | Stub de respuesta lenta | `wiremock/mappings/04-slow-response.json` | — | curl 4 → 201 UNKNOWN en 3.02 s; BD `PROVIDER_TIMEOUT`; 1 sola llamada |
| ACC-4 | README: levantar, curl de cada escenario, API, estados, arquitectura, persistencia, escalabilidad, limitaciones, IA | `README.md` (una sección por punto; índice al inicio) | — | lectura |
| ACC-5 | ADR de capas e inversión de dependencias | `docs/adr/0002-…` | — | — |
| ACC-5 | ADR de MVC + virtual threads | `docs/adr/0003-…` | — | — |
| ACC-5 | ADR de PostgreSQL y modelo | `docs/adr/0004-…` | — | — |
| ACC-5 | ADR de estados y resultado incierto | `docs/adr/0005-…` | — | — |
| ACC-5 | ADR de resiliencia | `docs/adr/0006-…` | — | — |
| ACC-5 | ADR de idempotencia | `docs/adr/0007-…` | — | — |
| ACC-5 | ADR de contrato de errores | `docs/adr/0008-…` | — | — |

## Commits sugeridos (Conventional Commits en español, sin Co-Authored-By)

```
build(docker): agrega Dockerfile multi-stage con capas de Spring Boot y usuario no root, y servicio app en compose
test(wiremock): agrega stubs del proveedor por accountId (aprobado, fondos insuficientes, 503 y respuesta lenta)
docs(adr): agrega ADRs 0002–0008 de capas, virtual threads, persistencia, estados, resiliencia, idempotencia y errores
docs: reescribe el README con instrucciones, escenarios, decisiones de diseño y uso de IA
```

`docs/sonar-decisiones.md` (sin trackear, del leader) no entra en estos commits.

---

# Vuelta 2

Origen: `review_delivery.md` (CHANGES_REQUESTED, 3 fallos en el README). Solo cambian `README.md`, este reporte y `current.md`. No toqué `src/`, `pom.xml`, `docker-compose.yml` ni `docs/sonar-decisiones.md`.

| # | Hallazgo | Cambio |
|---|---|---|
| 1 | README:41 decía que hacía falta `-v` para borrar los datos de Postgres | `README.md:41`: el apagado sugerido ahora es `docker compose down -v`. Aclara que el compose no declara un volumen con nombre, así que los datos no sobreviven a `down` (con o sin `-v`) y cada `up` arranca con la BD vacía; `-v` solo evita volúmenes anónimos huérfanos. No agregué volumen: los conteos de los curls 8a–8c dependen de partir de una BD vacía |
| 2 | RT04 omitía el origen de la configuración base | `README.md:432`: nuevo bullet "Configuración base de un proyecto previo del autor": versiones, dependencias, `application.yaml` y compose de partida, solo configuración y sin código, con enlace a `.harness/docs/reference-config.md`. `README.md:433`: "Sin solución de referencia para el código": los agentes no leyeron implementaciones del reto, tampoco la de ese proyecto; el código y los tests se escribieron desde la spec y el estándar a partir de esa configuración base. `README.md:455-456`: la IA escribió la configuración partiendo de esa base; la persona la aportó |
| 3 | README:429 generalizaba "los hallazgos de Sonar se corrigieron" | `README.md:429`: ahora dice "Los issues de código que reportó Sonar se corrigieron en el código, sin desactivar reglas". No menciona riesgos de dependencias ni `docs/sonar-decisiones.md` |
| Nota | Conteo de unitarios | Corregí 162 → **192** aquí y en `current.md`. El 162 salía de sumar una salida truncada. El total real sale de los XML de surefire: `grep -o 'tests="N"' TEST-*.xml` suma 192 |

## Verificación

`./.harness/init.sh` → `EXIT=0`. Surefire: 192 tests (suma de `TEST-*.xml`). Failsafe: 39 tests. 0 fallos y 0 errores.

## Commit sugerido

Sin cambio: los ajustes entran en `docs: reescribe el README con instrucciones, escenarios, decisiones de diseño y uso de IA`.
