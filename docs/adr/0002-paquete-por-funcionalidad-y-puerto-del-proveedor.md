# ADR-0002: Organizar por funcionalidad en capas e invertir la dependencia solo hacia el proveedor

**Estado:** Aceptada

## Contexto

La spec valora "separación de capas, inversión de dependencias, estructura coherente" y, a la vez, castiga la sobre-ingeniería ("Pragmatismo: no sobre-ingenierizar. Simple y extensible"). El servicio tiene una sola funcionalidad (`/transactions`) y una sola dependencia externa que cambia por razones ajenas al negocio: el proveedor `POST /provider/v1/execute`, que debe poder simularse (WireMock) y probarse con mocks (RT02).

## Decisión

- **Paquete por funcionalidad**, no por tipo: `transaction/` contiene controller, servicio, reglas, entidad, repositorio y el puerto; `transaction/dto/` los records HTTP; `common/web/` el contrato de errores y la paginación; `provider/http/` el adaptador.
- **Capas dentro de la funcionalidad**: `TransactionController` (solo traduce DTO ↔ comando y elige 200/201) → `TransactionService` (caso de uso) → `TransactionRepository` (Spring Data JPA). Las reglas RN01–RN03 viven en `TransactionRules`, sin dependencias de la capa web.
- **Inversión de dependencias solo donde aporta**: el puerto `TransactionProvider` vive en `transaction/` y devuelve el tipo sellado `ProviderResult` (`Approved`, `Rejected`, `Failed`, `Unknown`); no lanza excepciones. `HttpTransactionProvider` (en `provider/http/`) lo implementa. El servicio procesa el resultado con un `switch` exhaustivo sin `default`.
- Servicio, reglas y repositorio son clases concretas, sin interfaz.
- La entidad JPA `Transaction` es el modelo de dominio; no hay un modelo de dominio duplicado ni mappers por capa. Hacia afuera solo salen DTOs (`TransactionResponse`).
- **La arquitectura es ejecutable** (`ArchitectureTest`, ArchUnit): `transaction` no depende de `provider.http`; sin `@Autowired` en campos; ninguna clase que dependa (directa o transitivamente) del puerto lleva `@Transactional` (incluye meta-anotaciones). Cada regla tiene un fixture que demuestra que detecta su violación.

## Alternativas consideradas

- **Arquitectura hexagonal completa** (dominio puro + entidades JPA + mappers + puertos para repositorio): duplica modelos para una sola funcionalidad sin ganancia medible; contradice el pragmatismo pedido.
- **Paquetes por tipo** (`controllers/`, `services/`, `repositories/`): dispersa una funcionalidad en N carpetas y obliga a hacer públicos tipos que pueden ser package-private.
- **Interfaces para servicio y reglas**: una sola implementación; no habilitan nada que Mockito o la clase concreta no den ya.
- **Puerto que lanza excepciones** (`ProviderRejectedException`, `ProviderTimeoutException`…): el compilador no obliga a tratar cada caso y es fácil confundir un timeout con un fallo.

## Consecuencias

- Cambiar de proveedor o de protocolo toca solo `provider/http/`; el caso de uso no conoce HTTP.
- Agregar un resultado nuevo al tipo sellado rompe la compilación del servicio hasta tratarlo.
- `common/web` y `transaction` se conocen mutuamente (el handler traduce excepciones del dominio); aceptable con una sola funcionalidad. Con varias, las excepciones exportarían un `code` común.
- La entidad JPA hace de modelo de dominio: un cambio del esquema puede tocar el dominio. Se acepta a cambio de no mantener mappers.
