---
name: java-l3-standard
description: Estándar de ingeniería Java backend (nivel L3) para este repo. Checklist obligatorio que el implementer aplica antes de entregar y el reviewer valida. Úsalo al escribir o revisar cualquier clase en src/.
---

# Estándar Java L3

Contrato de calidad compartido. El implementer entrega la **tabla de auditoría** (al final) con ✅/❌/N/A por regla y `archivo:línea` como evidencia. El reviewer verifica cada fila; no acepta el self-report.

Si una regla choca con la spec (`.harness/docs/spec.md`), gana la spec y se documenta la excepción.

## A. Arquitectura

| ID | Regla | Por qué |
|----|-------|---------|
| A1 | Paquetes por funcionalidad (`transaction/`), no por tipo (`controllers/`, `services/`). | El cambio de una funcionalidad queda en un solo lugar. |
| A2 | Capas controller → service → repository. El controller no tiene lógica de negocio. | Cada capa se prueba aislada. |
| A3 | El proveedor externo se consume por una **interfaz (puerto) que vive en el paquete del dominio**; la implementación HTTP vive en `provider/http/`. | Inversión de dependencias: el dominio no conoce HTTP. |
| A4 | `provider/http/` depende del dominio, **nunca al revés**. Verificado con ArchUnit. | Evita que el protocolo se filtre al caso de uso. |
| A5 | El puerto devuelve un **tipo sellado** de resultados (aprobado, rechazado, falla, desconocido) y **no lanza excepciones**. El servicio lo procesa con `switch` exhaustivo. | El compilador obliga a tratar cada resultado. |
| A6 | Inversión de dependencias **solo donde aporta** (el proveedor). Nada de interfaces con una sola implementación para servicios o reglas. | Pragmatismo: la spec castiga el sobre-diseño. |
| A7 | DTOs de entrada/salida en `<feature>/dto/` como `record`. La entidad JPA no se expone en la API. | El contrato público cambia sin tocar el modelo persistido. |

## B. Dominio

| ID | Regla | Por qué |
|----|-------|---------|
| B1 | Las reglas de negocio viven en una clase propia, reciben solo los datos que evalúan y no dependen de la capa web. | Se prueban con tests unitarios puros. |
| B2 | Dinero en `BigDecimal`, comparado con `compareTo`, nunca con `equals` ni `double`. | `1.0` y `1.00` son iguales en valor, no en `equals`. |
| B3 | La entidad no expone setters: cambia de estado con métodos de negocio (`markExecuted`, `markRejected`…) y **una sola vez**. | Las transiciones inválidas son imposibles. |
| B4 | Estados explícitos, incluido el resultado incierto (timeout = desconocido, no fallido). | Un timeout no prueba que la operación no se aplicó. |
| B5 | Errores de negocio con código estable en inglés (`AMOUNT_TOO_LOW`) y mensaje descriptivo. | El cliente programa contra el código, no contra el texto. |

## C. Persistencia y concurrencia

| ID | Regla | Por qué |
|----|-------|---------|
| C1 | Esquema con migraciones Flyway; Hibernate en `ddl-auto: validate`. | El esquema es código versionado. |
| C2 | La transacción se registra en `PENDING` **antes** de llamar al proveedor. | Si el proceso muere a la mitad, queda rastro para conciliar. |
| C3 | **Ninguna conexión de BD se retiene durante la llamada HTTP**: nada de `@Transactional` que envuelva al proveedor; `open-in-view: false`. | Con alto volumen, el pool se agota esperando a un tercero. |
| C4 | Idempotencia opcional por header `Idempotency-Key`, garantizada por **constraint `UNIQUE` en BD**, no por lógica en memoria. La carrera se resuelve atrapando la violación y devolviendo la transacción ganadora. | Solo la BD es atómica entre réplicas. |
| C5 | Misma llave con otro payload = `409`, no un replay. | Es un error del cliente. |
| C6 | Índices alineados con los filtros de consulta; paginación con orden estable (`createdAt` + `id` como desempate). | Ninguna fila se repite ni se pierde entre páginas. |
| C7 | IDs UUID generados en la app (v7, ordenables por tiempo). | Sin ida y vuelta a la BD y con buena localidad en el índice. |

## D. Proveedor externo

| ID | Regla | Por qué |
|----|-------|---------|
| D1 | Cliente `RestClient` con timeouts explícitos de conexión y lectura, configurables. | Sin timeout, un proveedor lento cuelga el servicio. |
| D2 | Reintentos **solo** cuando la petición no llegó (conexión rechazada o timeout de conexión). Nunca tras timeout de lectura. | Reintentar algo que pudo aplicarse duplica dinero. |
| D3 | Circuit breaker; el retry lo envuelve. Los rechazos de negocio (4xx con cuerpo) **no** abren el circuito. | Un saldo insuficiente no es una falla del proveedor. |
| D4 | Toda respuesta o excepción se traduce al tipo sellado del puerto; ningún detalle HTTP sale del adaptador. | A3 + A5. |
| D5 | Resilience4j configurado en código o YAML, sin anotaciones mágicas que oculten el orden de los decoradores. | El orden retry/circuit breaker debe leerse. |

## E. API

| ID | Regla | Por qué |
|----|-------|---------|
| E1 | Validación de formato con Bean Validation en el DTO (`@Valid`); reglas de negocio en el servicio. | Formato = 400; negocio = 422. |
| E2 | Errores en formato **RFC 9457 `ProblemDetail`** desde un único `@RestControllerAdvice`, con propiedad `code` estable. Incluye los errores propios de Spring (JSON inválido, tipo incorrecto). | Contrato de errores uniforme. |
| E3 | `500` nunca expone stack traces ni mensajes internos; se registran en el log. | Seguridad. |
| E4 | `POST` exitoso responde `201` aunque el proveedor rechace: la transacción **se creó** con estado `REJECTED`. | El recurso existe; el estado comunica el resultado. |
| E5 | Respuesta de paginación con un DTO propio (`content`, `page`, `limit`, `totalElements`, `totalPages`), no el `Page` de Spring serializado. | Contrato estable. |
| E6 | `limit` acotado (p. ej. máx. 100). | Protege a la BD. |
| E7 | OpenAPI generado con springdoc a partir del código. | Documentación siempre al día. |

## F. Configuración y runtime

| ID | Regla | Por qué |
|----|-------|---------|
| F1 | Virtual threads habilitados; el pool de Hikari es el límite real hacia la BD. | I/O-bound con alto volumen. |
| F2 | Todo valor de entorno con `${VAR:default}`; propiedades del proveedor en un `@ConfigurationProperties` tipado (`record`). | Cambiar de entorno sin recompilar. |
| F3 | Logs con SLF4J, parametrizados (`{}`), sin datos sensibles. | Rendimiento y privacidad. |

## G. Tests

| ID | Regla | Por qué |
|----|-------|---------|
| G1 | Unitarios `*Test` (Surefire): reglas, entidad, servicio con mock del puerto. | Rápidos, corren en cada parada. |
| G2 | Adaptador HTTP probado contra WireMock: aprobado, rechazo, 5xx sin cuerpo, timeout, conexión rechazada con reintentos, circuito abierto. | Cada rama de D1–D4. |
| G3 | Controller con `@WebMvcTest`: códigos HTTP y forma del `ProblemDetail`. | Contrato de la API. |
| G4 | Integración `*IT` (Failsafe) con Testcontainers (Postgres) + WireMock: flujo completo y **carrera concurrente con la misma llave**. | Lo que los mocks no prueban. |
| G5 | Reglas de arquitectura con ArchUnit (A4, C3 y paquetes). | La arquitectura no depende de la memoria del revisor. |
| G6 | Valores frontera explícitos (`1.00`, `1.01`, `10000.00`, `10000.01`). | Las reglas fallan en los bordes. |
| G7 | Nombres que describen comportamiento (`@DisplayName` en español). Afirmar resultados concretos, nunca solo "no lanza". | El test documenta la regla. |

## H. Estilo

| ID | Regla | Por qué |
|----|-------|---------|
| H1 | Java 21: `record`, `sealed`, pattern matching en `switch`. | Menos código, más intención. |
| H2 | Inyección por constructor; campos `final`; sin `@Autowired` en campos. | Inmutable y testeable sin Spring. |
| H3 | Sin Lombok. | Java 21 cubre los casos con `record`. |
| H4 | Comentarios solo para el *por qué* no obvio. | El nombre explica el qué. |
| H5 | Sin código muerto, `System.out` ni TODOs sin feature asociada. | Repo limpio. |

---

## Tabla de auditoría (copiar al reporte)

```markdown
| Regla | Estado | Evidencia (`archivo:línea`) | Nota |
|-------|--------|------------------------------|------|
| A1 | ✅/❌/N/A | | |
| ... | | | |
```

Una fila por cada regla que la feature toca. Las que no aplican se marcan `N/A` con una razón de una línea.
