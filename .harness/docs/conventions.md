# Convenciones de código

> Homogeneidad extrema: el modelo predice mejor cuando el repo se parece a sí mismo en todas partes.
> El *qué* y el *por qué* de cada regla de calidad están en `.claude/skills/java-l3-standard/SKILL.md`. Aquí van nombres, estructura y forma.

## Stack

Fijo en `.claude/CLAUDE.md`. Nada fuera de esa tabla sin ADR.

## Paquetes

Paquete raíz: `mx.gilortiz.transactions`.

```
src/main/java/mx/gilortiz/transactions/
├── TransactionsApplication.java
├── transaction/            dominio + caso de uso + API de la funcionalidad
│   ├── dto/                records de entrada/salida HTTP
│   └── ...
├── provider/http/          adaptador HTTP del proveedor externo
└── common/web/             transversal HTTP (manejo de errores, paginación)
```

- **No** crear `service/`, `controller/`, `util/`, `helper/` globales.
- **No** crear subpaquetes nuevos sin actualizar `docs/architecture.md` y la regla ArchUnit.

## Nombres

| Tipo | Convención | Ejemplo |
|---|---|---|
| Controller | `<Recurso>Controller` | `TransactionController` |
| Servicio | `<Recurso>Service` | `TransactionService` |
| Reglas | `<Recurso>Rules` | `TransactionRules` |
| Puerto | `<Recurso>Provider` (interfaz) | `TransactionProvider` |
| Adaptador | `<Protocolo><Puerto>` | `HttpTransactionProvider` |
| DTO entrada | `Create<Recurso>Request` | `CreateTransactionRequest` |
| DTO salida | `<Recurso>Response` | `TransactionResponse` |
| Comando interno | `<Verbo><Recurso>Command` | `ExecuteTransactionCommand` |
| Excepción | `<Causa>Exception` | `BusinessRuleException` |
| Migración | `V<n>__<descripcion>.sql` | `V1__create_transactions.sql` |
| Test unitario | `<Clase>Test` | `TransactionRulesTest` |
| Test integración | `<Tema>IT` | `TransactionFlowIT` |
| Códigos de error | `UPPER_SNAKE` en inglés | `AMOUNT_TOO_LOW` |
| Columnas SQL | `snake_case` | `balance_after` |
| JSON | `camelCase`, literal de la spec | `providerTransactionId` |

## Idioma

- Código (clases, métodos, variables, códigos de error): **inglés**.
- Comentarios, Javadoc, `@DisplayName`, mensajes de error al cliente, logs y documentación: **español**.

## Java

- Java 21: `record` para DTOs, comandos y resultados; `sealed interface` para resultados con variantes; `switch` con pattern matching.
- Inyección por constructor, campos `private final`.
- `Optional` solo como retorno, nunca como parámetro ni campo.
- `BigDecimal` para dinero; `Instant` para fechas (UTC).
- Visibilidad mínima: package-private por defecto si nada fuera del paquete lo usa.

## Configuración

- `src/main/resources/application.yaml` (YAML, no `.properties`).
- Valores de entorno: `${VAR:default}`.
- Propiedades propias bajo un prefijo (`provider.*`) con `@ConfigurationProperties` en un `record`.

## Tests

- JUnit 5 + AssertJ. Mockito solo para el puerto del proveedor y el repositorio en tests de servicio.
- `@DisplayName` en español que describe el comportamiento: `"un DEBIT de 10,000.01 se rechaza"`.
- `@ParameterizedTest` para valores frontera.
- Unitarios `*Test` (Surefire, `./mvnw test`). Integración `*IT` (Failsafe, `./mvnw verify`).
- Una prueba afirma un resultado concreto: estado, código HTTP, `code` del error, número de llamadas al proveedor.

## Commits

Conventional Commits en español: `feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `build:`, `chore:`. Un commit por feature cerrada como mínimo.

## Comentarios

Por defecto, ninguno. Solo para explicar un *por qué* no obvio (por ejemplo, por qué no hay `@Transactional`).

## Anti-patrones

- ❌ Lógica de negocio en el controller.
- ❌ Capturar `Exception` para devolver `null`.
- ❌ `@Transactional` en el servicio que llama al proveedor.
- ❌ Reintentar timeouts de lectura.
- ❌ Exponer la entidad JPA en la API.
- ❌ `double`/`float` para dinero.
- ❌ Tests que solo verifican que algo "no es null".
