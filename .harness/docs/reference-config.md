# Configuración de referencia

> Extraída por el leader (2026-10-28) de un proyecto previo del autor, **solo configuración** (build, runtime, compose). Ningún código de `src/main/java` ni `src/test/java`. El repo de origen sigue bloqueado para los agentes: usa este archivo.
>
> Es punto de partida, no contrato: lo que exija `feature_list.json`, `CLAUDE.md` o el estándar L3 manda sobre esto.

## Build

- Maven Wrapper `3.3.4`, `distributionType=only-script`, Maven `3.10.0`.
- Parent `org.springframework.boot:spring-boot-starter-parent:4.1.2`, `java.version=21`.
- groupId `mx.gilortiz`, artifactId `transactions`.

### Dependencias (Boot 4 = starters modulares)

| Ámbito | Artefacto | Versión |
|---|---|---|
| main | `spring-boot-starter-webmvc` | BOM |
| main | `spring-boot-starter-data-jpa` | BOM |
| main | `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` | BOM |
| main | `spring-boot-starter-validation` | BOM |
| main | `spring-boot-starter-actuator` | BOM |
| main | `io.github.resilience4j:resilience4j-circuitbreaker`, `resilience4j-retry` (núcleo, sin starter) | `2.4.0` |
| main | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | `3.2.0` |
| runtime | `org.postgresql:postgresql` | BOM |
| test | `spring-boot-starter-{webmvc,data-jpa,flyway,validation,actuator}-test` | BOM |
| test | `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`, `testcontainers-postgresql` (Testcontainers 2.x) | BOM |
| test | `org.wiremock:wiremock-standalone` | `3.13.0` |

**Falta en la referencia y lo exige feature 1:** ArchUnit (`com.tngtech.archunit:archunit-junit5`) y `maven-failsafe-plugin` para `*IT`.

## `application.yaml`

```yaml
spring:
  application.name: transactions
  threads.virtual.enabled: true
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/transactions}
    username: ${DB_USER:transactions}
    password: ${DB_PASSWORD:transactions}
    hikari.maximum-pool-size: ${DB_POOL_SIZE:20}   # con virtual threads, el pool es la contrapresión hacia la BD
  jpa:
    open-in-view: false
    hibernate.ddl-auto: validate                    # Flyway es dueño del esquema
    properties.hibernate.jdbc.time_zone: UTC
management.endpoints.web.exposure.include: health,info
provider:
  base-url: ${PROVIDER_BASE_URL:http://localhost:8089}
  connect-timeout: 1s
  read-timeout: 3s                                  # excedido → UNKNOWN
```

## Docker Compose (`docker-compose.yml`)

- `postgres`: `postgres:17-alpine`, db/user/password `transactions`, `5432:5432`, healthcheck `pg_isready -U transactions -d transactions`.
- `provider-mock`: `wiremock/wiremock:3.13.0`, `8089:8080`, volumen `./wiremock:/home/wiremock`, `--disable-banner`.
- `app` (feature 7): `build: .`, `8080:8080`, env `DB_URL=jdbc:postgresql://postgres:5432/transactions`, `PROVIDER_BASE_URL=http://provider-mock:8080`, `depends_on` postgres `service_healthy`, healthcheck sobre `/actuator/health`.

## `.dockerignore`

`target/`, `.git/`, `.idea/`, `*.iml`, `.DS_Store`, `wiremock/`, `docker-compose.yml`, `Dockerfile`.
