# ADR-0006: Cliente del proveedor con timeouts, reintento solo de conexión y circuit breaker programático

**Estado:** Aceptada

## Contexto

La spec pide un cliente HTTP desacoplado y valora "resiliencia (timeouts, reintentos, circuit breaker)". Es un sistema de dinero: reintentar una petición que el proveedor pudo aplicar duplica el movimiento. Además, un saldo insuficiente es una respuesta normal del proveedor, no una señal de que esté caído.

## Decisión

- **`RestClient`** construido desde el `RestClient.Builder` de Boot (`spring-boot-starter-restclient`, clonado: conserva Jackson, customizers y observabilidad) sobre `JdkClientHttpRequestFactory` (HTTP/1.1 forzado).
- **Timeouts explícitos y configurables**: `connect-timeout` 1 s (`PROVIDER_CONNECT_TIMEOUT`) y `read-timeout` 3 s (`PROVIDER_READ_TIMEOUT`). Al arrancar se valida `readTimeout > connectTimeout`.
- **Resilience4j núcleo, configurado en código** (`resilience4j-retry` y `resilience4j-circuitbreaker`, sin starter ni anotaciones). Orden legible: `Retry(CircuitBreaker(llamada))`; cada intento pasa por el circuito.
- **Retry solo cuando la petición no llegó**: conexión rechazada, timeout de conexión, host desconocido, sin ruta. 3 intentos con backoff exponencial y jitter (200 ms × 2, ±50 %). **Nunca** tras timeout de lectura, 5xx o conexión cortada a mitad.
- **Circuit breaker**: ventana de 20 llamadas, mínimo 10, umbral 50 %, 30 s abierto. Cuentan como falla `Failed` y `Unknown` (incluidos los timeouts de lectura: un proveedor lento en masa es justo lo que el circuito debe cortar). **`Rejected` y `Approved` no cuentan** (D3). Circuito abierto → `Failed(PROVIDER_CIRCUIT_OPEN)` sin petición HTTP.
- Toda respuesta o excepción se traduce a `ProviderResult` dentro del adaptador; cualquier `RuntimeException` imprevista → `Unknown(PROVIDER_UNEXPECTED_ERROR)` con log.
- Todos los parámetros de retry y circuito son `${VAR:default}` en `provider.*` (`ProviderProperties`, record validado en su constructor).

## Alternativas consideradas

- **Anotaciones `@Retry`/`@CircuitBreaker`** (starter de Resilience4j o Spring Cloud): ocultan el orden de los aspectos y qué excepciones se reintentan.
- **Reintentar 5xx o timeouts de lectura**: la petición llegó; el proveedor pudo aplicarla.
- **Espera fija entre reintentos**: sincroniza a los clientes contra un proveedor que se recupera; el jitter los dispersa.
- **Excluir los timeouts del circuito**: un proveedor que responde en 10 s seguiría reteniendo cada request 3 s.
- **`RestTemplate`, Feign, `WebClient`**: prohibidos por el stack; `RestClient` es el cliente síncrono actual de Spring.
- **`RestClient.builder()` estático**: ignora la configuración de Boot (Jackson, observabilidad, customizers).

## Consecuencias

- Ningún reintento puede duplicar un movimiento; los casos inciertos terminan en `UNKNOWN` (ADR-0005).
- Con el proveedor caído, las requests fallan rápido (`FAILED`) en lugar de esperar 3 s cada una.
- El circuito es por instancia: con varias réplicas, cada una aprende sola que el proveedor está caído.
- En local, repetir muchas veces los escenarios `acc-provider-down` o `acc-slow` puede abrir el circuito durante 30 s; las siguientes requests (de cualquier cuenta) quedan `FAILED` con `PROVIDER_CIRCUIT_OPEN`.
- `spring-boot-starter-restclient` también autoconfigura un `RestTemplateBuilder`; no se usa.
