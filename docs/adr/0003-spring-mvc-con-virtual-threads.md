# ADR-0003: Usar Spring MVC bloqueante sobre virtual threads

**Estado:** Aceptada

## Contexto

El servicio es I/O-bound: cada `POST /transactions` hace un INSERT, una llamada HTTP al proveedor (hasta el `read-timeout` de 3 s) y un UPDATE. La spec pide considerar "alto volumen (millones de transacciones diarias)". Con el modelo de un hilo de plataforma por request, un proveedor lento agota el pool de hilos de Tomcat aunque la CPU esté ociosa.

## Decisión

- **Spring MVC** (`spring-boot-starter-webmvc`) con código bloqueante y secuencial.
- **Virtual threads** habilitados (`spring.threads.virtual.enabled: true`): cada request corre en un hilo virtual; mientras espera al proveedor o a la BD no ocupa un hilo de plataforma.
- **El pool de Hikari es la contrapresión real hacia la BD** (`DB_POOL_SIZE`, 20 por defecto). Por eso ninguna conexión se retiene durante la llamada al proveedor (ver ADR-0005): con 2 conexiones, 10 requests pueden esperar al proveedor a la vez (`TransactionFlowIT.providerCallHoldsNoDatabaseConnection`).
- Cliente HTTP: `RestClient` (síncrono) sobre el `HttpClient` del JDK, que también se lleva bien con hilos virtuales.

## Alternativas consideradas

- **Spring WebFlux / Reactor**: misma escalabilidad de I/O, pero con un modelo de programación reactivo que complica el código, los tests y el uso de JPA (bloqueante). Prohibido en el stack fijo.
- **MVC con hilos de plataforma y un pool grande**: cada hilo cuesta ~1 MB de stack; escalar a miles de esperas concurrentes cuesta memoria y cambios de contexto.
- **Llamada asíncrona al proveedor (cola + worker)**: desacopla la latencia, pero la spec pide responder la transacción con su estado final en el mismo request.

## Consecuencias

- Código lineal y fácil de leer y probar; los tests usan `RestClient` y `@WebMvcTest` normales.
- Un bloque `synchronized` largo podría fijar (pin) el hilo virtual a su portador en Java 21; el código propio no usa `synchronized`.
- La concurrencia hacia la BD queda limitada por Hikari, no por Tomcat: dimensionar `DB_POOL_SIZE` es la perilla de capacidad.
