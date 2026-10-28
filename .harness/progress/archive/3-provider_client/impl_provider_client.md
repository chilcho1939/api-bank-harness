# Implementación — feature 3 `provider_client` (RN05, RN06)

- **Rama:** `feat/3-provider_client` (sin commit)
- **Estado:** `in_progress` (pendiente del reviewer)
- **Verificación:** `./.harness/init.sh` → exit 0 (`verify verde (unitarios + integración + ArchUnit)`, `Entorno listo.`)

## Archivos

### Creados — `src/main`

| Archivo | Rol |
|---|---|
| `transaction/TransactionProvider.java` | Puerto (A3). `ProviderResult execute(ProviderRequest)`; no lanza excepciones. |
| `transaction/ProviderRequest.java` | Record con los 4 campos que el proveedor necesita. |
| `transaction/ProviderResult.java` | `sealed interface`: `Approved(transactionId, balance, executedAt)`, `Rejected(code, message)`, `Failed(code, message)`, `Unknown(code, message)` (A5). |
| `provider/http/HttpTransactionProvider.java` | Adaptador: `RestClient` sobre `JdkClientHttpRequestFactory`, `Retry(CircuitBreaker(llamada))` programático, traducción a `ProviderResult`. `AutoCloseable` (cierra el `HttpClient`). |
| `provider/http/ExecuteRequest.java` | Cuerpo literal de `POST /provider/v1/execute`: `accountId`, `type`, `amount`, `currency`. |
| `provider/http/ExecuteResponse.java` | Cuerpo de respuesta literal: `transactionId`, `status`, `balance`, `executedAt`, `code`, `message`. |
| `provider/http/ProviderUnreachableException.java` | Señal interna "la petición no llegó"; es la única excepción reintentable. No sale del adaptador. |
| `provider/http/ProviderProperties.java` | `@ConfigurationProperties("provider")` en un `record` con `retry` y `circuit-breaker` anidados. |
| `provider/http/ProviderClientConfiguration.java` | `@EnableConfigurationProperties` + `@Bean TransactionProvider`. |

### Creados — `src/test`

| Archivo | Tests |
|---|---|
| `provider/http/HttpTransactionProviderTest.java` | 19 (WireMock en proceso, 3 `@Nested`) |
| `provider/http/ProviderClientConfigurationTest.java` | 2 (`ApplicationContextRunner` + `application.yaml` real + WireMock) |
| `architecture/fixture/transaction/TransactionalProviderCallerFixture.java` | Fixture que viola a propósito la regla `@Transactional` |

### Modificados

- `src/main/resources/application.yaml`: bloque `provider.*` (base-url `${PROVIDER_BASE_URL:http://localhost:8089}`, connect-timeout 1s, read-timeout 3s, retry y circuit-breaker, todo con `${VAR:default}`).
- `src/test/java/.../architecture/ArchitectureTest.java`: regla `NO_TRANSACTIONAL_AROUND_PROVIDER` y su test de detección.
- `.harness/progress/current.md`.

## Dependencias

Ninguna nueva. Se usan `resilience4j-retry` y `resilience4j-circuitbreaker` (núcleo, ya en el pom), `spring-web` (`RestClient`) y `wiremock-standalone` (test). No se agregó `spring-boot-starter-restclient`: en Boot 4 el bean `RestClient.Builder` vive en ese módulo, que no está en el pom, así que el adaptador usa `RestClient.builder()` directamente. Sin ADR.

## Verificación

```
./.harness/init.sh
[OK]    feature_list.json válido (2/7 done)
[OK]    verify verde (unitarios + integración + ArchUnit)
[OK]    Entorno listo.
```

Conteo (de `target/surefire-reports` y `target/failsafe-reports`):

| Suite | Tests | Fallas |
|---|---|---|
| `ArchitectureTest` | 6 (+2 nuevos) | 0 |
| `HttpTransactionProviderTest` | 19 (nuevo) | 0 |
| `ProviderClientConfigurationTest` | 2 (nuevo) | 0 |
| `TransactionRulesTest` | 30 | 0 |
| **Unitarios (Surefire)** | **57** | 0 |
| `ApplicationContextIT` (Failsafe, Testcontainers) | 3 | 0 |

`./mvnw test -Dtest=HttpTransactionProviderTest` → `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`.
Nota: el `.txt` de Surefire de la clase externa muestra `Tests run: 0` porque los casos se reportan en las clases `@Nested`; el XML trae los 19 `<testcase>` y el total de Maven los cuenta.

## Decisiones

### Excepción / respuesta → resultado → ¿retry? → ¿cuenta para el circuito?

Cada fila se observó en ejecución real contra WireMock, no se dedujo.

| Situación | Lo que observa el adaptador | Resultado del puerto | ¿Retry? | ¿Falla para el circuito? |
|---|---|---|---|---|
| 200 con `status: APPROVED`, `transactionId` y `balance` | respuesta | `Approved(transactionId, balance, executedAt)` | No | No (éxito) |
| 200 sin aprobación válida | respuesta | `Unknown(PROVIDER_INVALID_RESPONSE)` | No | Sí |
| 4xx con `code` y `message` | respuesta | `Rejected(code, message)` del proveedor | No | **No** (rechazo de negocio, D3) |
| 4xx sin cuerpo de error | respuesta | `Failed(PROVIDER_INVALID_RESPONSE)` | No | Sí |
| 5xx sin cuerpo | respuesta | `Failed(PROVIDER_ERROR, "…HTTP 503 sin cuerpo")` | **No** | Sí |
| 5xx con `code` y `message` | respuesta | `Failed(code, message)` del proveedor (RN06) | No | Sí |
| Conexión rechazada (puerto cerrado) | `ResourceAccessException` ← `java.net.ConnectException` | `Failed(PROVIDER_UNAVAILABLE)` tras 3 intentos | **Sí** | Sí (cada intento) |
| Timeout de conexión / host desconocido / sin ruta | `HttpConnectTimeoutException`, `UnknownHostException`, `NoRouteToHostException` | `Failed(PROVIDER_UNAVAILABLE)` | Sí | Sí |
| Timeout de lectura (`withFixedDelay`) | `ResourceAccessException` ← `java.net.http.HttpTimeoutException` | `Unknown(PROVIDER_TIMEOUT)` | **No** | Sí |
| Conexión cortada a mitad (`Fault.CONNECTION_RESET_BY_PEER`) | `ResourceAccessException` ← `IOException` ("header parser received no bytes") | `Unknown(PROVIDER_COMMUNICATION_ERROR)` | No | Sí |
| Otra `RestClientException` | excepción | `Unknown(PROVIDER_COMMUNICATION_ERROR)` | No | Sí |
| Circuito abierto | `CallNotPermittedException` | `Failed(PROVIDER_CIRCUIT_OPEN)`, sin petición HTTP | No | n/a |

**Qué cuenta como falla y por qué:**
- La regla es "falla técnica sí, decisión de negocio no". `recordResult` marca `Failed`/`Unknown` y `recordExceptions` marca `ProviderUnreachableException`. `Rejected` y `Approved` son llamadas exitosas: un saldo insuficiente no indica que el proveedor esté caído (D3).
- **Los timeouts de lectura sí cuentan.** Un proveedor que se pone lento en masa es justo el caso del que el circuito debe protegernos: con alto volumen, cada petición retiene un hilo y una petición de entrada durante `read-timeout` (3 s). Contarlos no causa reintentos (ese es otro decorador) ni cambia el resultado (sigue siendo UNKNOWN). El test `readTimeoutsOpenCircuit` lo documenta.
- **Solo se reintenta `ProviderUnreachableException`** (D2): conexión rechazada, timeout de conexión, host desconocido. En todos esos casos la petición no llegó. El reset a mitad de la petición **no** se reintenta: WireMock lo produce después de recibir la petición, así que el proveedor pudo aplicarla; por eso es `Unknown`. (El prompt sugería `CONNECTION_RESET_BY_PEER` como ejemplo de conexión rechazada; lo descarté para el retry y usé un puerto cerrado.)
- **Los 5xx no se reintentan:** la petición llegó y el proveedor pudo aplicarla a medias. Se mapean a FAILED, como indica el diagrama de estados de `docs/architecture.md`.

### Otras decisiones

- **Orden de decoradores `Retry(CircuitBreaker(send))`** (D3, D5): está en `HttpTransactionProvider.java:104-105`. Cada intento pasa por el circuito. Si el circuito se abre a mitad de los reintentos, `CallNotPermittedException` no es reintentable y el resultado es `PROVIDER_CIRCUIT_OPEN` (lo ejercita `connectionRefusedOpensCircuit`).
- **`exchange(...)` en lugar de `retrieve()`**: el adaptador lee estado y cuerpo él mismo, sin los manejadores de estado por defecto. Así ningún `HttpStatusCodeException` sale del adaptador (D4). Un cuerpo vacío o que no es JSON se trata como "sin cuerpo" (`readBody`) y entonces decide el código HTTP.
- **5xx con cuerpo → `Failed(code, message)` del proveedor**, no `Rejected`. RN06 exige persistir `code` y `message` para 4xx **y** 5xx, y ambos variantes los llevan; el estado (FAILED) sigue el diagrama de arquitectura.
- **Códigos propios del adaptador** (`PROVIDER_UNAVAILABLE`, `PROVIDER_CIRCUIT_OPEN`, `PROVIDER_ERROR`, `PROVIDER_TIMEOUT`, `PROVIDER_COMMUNICATION_ERROR`, `PROVIDER_INVALID_RESPONSE`): UPPER_SNAKE en inglés y mensaje en español. `Failed`/`Unknown` siempre llevan `code` y `message`, para que la feature 4 los persista igual que un rechazo.
- **HTTP/1.1 forzado** en el `HttpClient`: el cliente JDK intenta por defecto el upgrade a h2c sobre `http://`.
- **`read-timeout`** se aplica con `JdkClientHttpRequestFactory#setReadTimeout` (timeout por petición del JDK). **`connect-timeout`** se aplica con `HttpClient.Builder#connectTimeout`.
- **`HttpTransactionProvider#circuitBreaker()` es package-private y solo para tests.** Con un puerto cerrado, WireMock no puede contar intentos, así que los 3 intentos se afirman con `getNumberOfFailedCalls() == 3` del circuito (cada intento pasa por él).
- **Retry y circuit-breaker son configurables** en `provider.retry.*` / `provider.circuit-breaker.*`, además de lo que pide la acceptance (timeouts y base-url). Así los tests usan ventanas pequeñas sin hacks. Valores por defecto: 3 intentos, espera de 200 ms, ventana de 20, mínimo de 10 llamadas, 50 % de fallas, 30 s abierto.
- **Regla ArchUnit `NO_TRANSACTIONAL_AROUND_PROVIDER`** (`ArchitectureTest.java:55`): las clases que dependen directamente de un tipo asignable a `TransactionProvider` no pueden tener `@Transactional` (Spring ni Jakarta) en la clase ni en sus métodos. Hoy ya aplica a `HttpTransactionProvider` y `ProviderClientConfiguration`, así que **no** lleva `allowEmptyShould`. Un fixture prueba que detecta la violación. Limitación conocida: solo mira dependencias directas. Si en la feature 4 el servicio llama al puerto a través de otra clase, la regla habría que extenderla.
- **Sin smoke `curl`**: esta feature no expone endpoints (llegan en la feature 5). La verificación manual equivalente son los tests contra WireMock real.

## Tabla de auditoría Java L3

| Regla | Estado | Evidencia (`archivo:línea`) | Nota |
|-------|--------|------------------------------|------|
| A1 | ✅ | `transaction/TransactionProvider.java:1`, `provider/http/HttpTransactionProvider.java:1` | Puerto en `transaction`, adaptador en `provider/http` (paquetes de `docs/architecture.md`). |
| A2 | N/A | — | Sin controller/servicio en esta feature. |
| A3 | ✅ | `transaction/TransactionProvider.java:10`; `provider/http/HttpTransactionProvider.java:33` | La interfaz vive en el dominio; la implementación HTTP en `provider/http`. |
| A4 | ✅ | `ArchitectureTest.java:35`, `:65` | `DOMAIN_DOES_NOT_DEPEND_ON_PROVIDER_HTTP` pasa con clases reales en ambos paquetes. |
| A5 | ✅ | `transaction/ProviderResult.java:10-40` | `sealed interface` con 4 variantes; el puerto declara que no lanza; el adaptador atrapa `ProviderUnreachableException`/`CallNotPermittedException` (`HttpTransactionProvider.java:108-115`) y `RestClientException` (`:128-133`). |
| A6 | ✅ | `transaction/TransactionProvider.java:10` | La única interfaz nueva es el puerto. |
| A7 | N/A | — | DTOs HTTP de la API en la feature 5; `ExecuteRequest`/`ExecuteResponse` son del adaptador, package-private. |
| B2 | ✅ | `ExecuteRequest.java:9`, `ProviderResult.java:13`, `HttpTransactionProviderTest.java:93` | `amount`/`balance` en `BigDecimal`; el test compara con `isEqualByComparingTo`. |
| B4 | ✅ | `ProviderResult.java:40`; `HttpTransactionProvider.java:173-176` | Variante `Unknown` explícita; timeout → `Unknown`, nunca `Failed`. |
| B5 | ✅ | `HttpTransactionProvider.java:37-42` | Códigos estables UPPER_SNAKE en inglés, mensajes en español. |
| C3 | ✅ | `ArchitectureTest.java:55`, `:77`, `:83` | Regla ArchUnit "sin `@Transactional` en dependientes del puerto" + fixture que la viola. |
| D1 | ✅ | `HttpTransactionProvider.java:64`, `:67`; `application.yaml` (bloque `provider`) | `connectTimeout` y `readTimeout` explícitos y configurables. |
| D2 | ✅ | `HttpTransactionProvider.java:81`, `:170-171`, `:184-188` | Solo `ProviderUnreachableException` (conexión) es reintentable; tests `:176`, `:189`, `:202`, `:116`. |
| D3 | ✅ | `HttpTransactionProvider.java:93-97`, `:104-105` | El retry envuelve al CB; `Rejected` no cuenta como falla (test `:240`). |
| D4 | ✅ | `HttpTransactionProvider.java:146-166`, `:169-182` | Toda respuesta o excepción → `ProviderResult`; `exchange` evita las excepciones de estado HTTP. |
| D5 | ✅ | `HttpTransactionProvider.java:77-100` | `RetryConfig`/`CircuitBreakerConfig` en código; sin anotaciones ni starter. |
| E* | N/A | — | Sin API REST en esta feature. |
| F2 | ✅ | `ProviderProperties.java:11-12`; `ProviderClientConfiguration.java:9`; `application.yaml` | `record` tipado con `@ConfigurationProperties("provider")`; todo valor con `${VAR:default}`. |
| F3 | ✅ | `HttpTransactionProvider.java:109`, `:113`, `:129`, `:174`, `:179` | SLF4J parametrizado; no se loguea `accountId` ni el monto. |
| G1 | ✅ | `ProviderClientConfigurationTest.java:36`, `:50` | Unitarios `*Test` (Surefire). |
| G2 | ✅ | `HttpTransactionProviderTest.java:86`, `:102`, `:116`, `:176`, `:189`, `:223` | WireMock real: aprobado, rechazo, 5xx sin cuerpo, timeout, conexión rechazada con reintentos, circuito abierto. |
| G5 | ✅ | `ArchitectureTest.java:55`, `:77` | A4 + C3 verificadas con ArchUnit. |
| G7 | ✅ | `HttpTransactionProviderTest.java` (todos) | `@DisplayName` en español; cada test afirma resultado, código y número de peticiones. |
| H1 | ✅ | `ProviderResult.java:10`; `ProviderProperties.java:12` | `sealed` + `record`. |
| H2 | ✅ | `HttpTransactionProvider.java:45-56` | Campos `private final`, constructor; ArchUnit `NO_FIELD_INJECTION` verde. |
| H3 | ✅ | `pom.xml` | Sin Lombok. |
| H4 | ✅ | `HttpTransactionProvider.java:61`, `:94-95`, `:139`, `:180` | Comentarios solo para el por qué (h2c, qué cuenta para el CB, cuerpo ilegible, reset incierto). |
| H5 | ✅ | — | Sin `System.out`, TODOs ni código muerto. |

## Pre-matriz de reglas

Las rutas son relativas a `src/main/java/mx/gilortiz/transactions/` y `src/test/java/mx/gilortiz/transactions/`.

| Regla | Qué exige | Implementación (`archivo:línea`) | Test que la afirma (`archivo:línea`) | Verificación manual (`curl`) |
|-------|-----------|----------------------------------|--------------------------------------|------------------------------|
| Contrato | `POST /provider/v1/execute` con `{accountId, type, amount, currency}` literal | `provider/http/HttpTransactionProvider.java:35`, `:121-125`; `provider/http/ExecuteRequest.java:9-13` | `provider/http/HttpTransactionProviderTest.java:68` (`equalToJson` + `"amount":1500.00` + sin `description`) | N/A: sin endpoint propio hasta la feature 5 |
| RN05-aprobado | 200 `APPROVED` → resultado con `transactionId` (→ `providerTransactionId`) | `HttpTransactionProvider.java:147-149`; `ExecuteResponse.java:24-26` | `HttpTransactionProviderTest.java:86` | N/A (feature 5/7) |
| RN05-saldo | `balance` del proveedor (→ `balanceAfter`) llega al resultado | `ProviderResult.java:13`; `HttpTransactionProvider.java:149` | `HttpTransactionProviderTest.java:86` (`balance == 5500.00`) | N/A |
| RN05-persistencia | Se **persiste** con `providerTransactionId` y `balanceAfter` | — | — | **BLOCKED**: la persistencia es de la feature 4 (`execution_service`); aquí solo se entregan los datos en `Approved` |
| RN06-4xx | 4xx → `code` y `message` del proveedor | `HttpTransactionProvider.java:153-157` | `HttpTransactionProviderTest.java:102` (400, 402, 409, 422) | N/A |
| RN06-5xx-con-cuerpo | 5xx → `code` y `message` del proveedor | `HttpTransactionProvider.java:159-161` | `HttpTransactionProviderTest.java:131` | N/A |
| RN06-5xx-sin-cuerpo | 5xx sin cuerpo → falla (con código propio) | `HttpTransactionProvider.java:162-163` | `HttpTransactionProviderTest.java:116` (500, 502, 503; 1 petición) | N/A |
| RN06-persistencia | Se **persiste** con `code` y `message` | — | — | **BLOCKED**: feature 4; `Rejected`/`Failed` ya traen `code` y `message` |
| Timeout | Timeout de lectura → desconocido | `HttpTransactionProvider.java:173-176` | `HttpTransactionProviderTest.java:176` | N/A |
| Timeout-sin-retry | Timeout de lectura NO se reintenta | `HttpTransactionProvider.java:81` (solo `ProviderUnreachableException`) | `HttpTransactionProviderTest.java:176` (`verify(1, …)`) | N/A |
| Conexión-retry | Conexión rechazada → 3 intentos → falla | `HttpTransactionProvider.java:80-81`, `:108-111`, `:185` | `HttpTransactionProviderTest.java:189` (`getNumberOfFailedCalls() == 3`, `PROVIDER_UNAVAILABLE`) | N/A |
| CB-abierto | Circuito abierto no llama al proveedor | `HttpTransactionProvider.java:104-105`, `:112-115` | `HttpTransactionProviderTest.java:223` (`verify(0, …)` tras abrir), `:266` | N/A |
| CB-negocio | Rechazos de negocio no abren el circuito | `HttpTransactionProvider.java:96-97` | `HttpTransactionProviderTest.java:240` (5 × 422 → `CLOSED`, 5 peticiones) | N/A |
| Config | Timeouts y base-url con `@ConfigurationProperties` | `provider/http/ProviderProperties.java:11-17`; `ProviderClientConfiguration.java:9`; `application.yaml` | `provider/http/ProviderClientConfigurationTest.java:36` (defaults 8089/1s/3s), `:50` (base-url y read-timeout sobreescritos llegan al cliente) | N/A |
| WireMock | Tests con WireMock para cada rama | — | `HttpTransactionProviderTest.java` (19 casos), `ProviderClientConfigurationTest.java:50` | N/A |
| C3/ArchUnit | Ninguna clase que dependa del puerto usa `@Transactional` | `architecture/ArchitectureTest.java:55` (test) | `ArchitectureTest.java:77` (producción), `:83` (fixture detectado) | N/A |

## Commits sugeridos (Conventional Commits, en español)

```
feat(transaction): agrega puerto TransactionProvider con resultado sellado
feat(provider): agrega adaptador HTTP con timeouts, reintentos y circuit breaker
test(architecture): prohíbe @Transactional en clases que dependen del puerto del proveedor
```

O uno solo:

```
feat(provider): agrega cliente del proveedor con resiliencia y resultado sellado (RN05, RN06)
```

---

# Vuelta 2: correcciones del `/code-review`

Origen: `.harness/progress/review_provider_client_codereview.md`. Se aplicaron los 10 ítems marcados "Corregir" (#4 parcial, como se indicó). Las rutas son relativas a `src/main/java/mx/gilortiz/transactions/provider/http/` (main) o `src/test/java/mx/gilortiz/transactions/` (test).

## Verificación

```
./.harness/init.sh  → EXIT=0
[OK]    verify verde (unitarios + integración + ArchUnit)
[OK]    Entorno listo.
./mvnw test → Tests run: 89, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
Failsafe: ApplicationContextIT tests="3", 0 fallas
```

Suites: `TransactionRulesTest` 30, `HttpTransactionProviderTest` 30 (19 + 5 + 6 en los `@Nested`), `ProviderPropertiesTest` 18 (nuevo), `ProviderClientConfigurationTest` 4, `ArchitectureTest` 7. **Total: 89 unitarios + 3 IT.** `HttpTransactionProviderTest` sigue tardando unos 3 s: los tests de backoff leen la `IntervalFunction` configurada, sin esperar en tiempo real.

Nota: la salida de `init.sh` muestra una línea `ERROR … Error no previsto al llamar al proveedor`. Es el `log.error` esperado del test `unexpectedRuntimeExceptionIsUnknown`, no una falla; el `grep` de `init.sh` la recoge porque contiene "ERROR".

## Cambio por ítem

| # | Hallazgo | Cambio | Test |
|---|---|---|---|
| 1 | Un 504 sin cuerpo no prueba que la operación no se aplicó | `HttpTransactionProvider.java:175-178`: 504 sin cuerpo → `Unknown(PROVIDER_GATEWAY_TIMEOUT)`, sin retry, cuenta para el circuito. 500/502/503 sin cuerpo siguen → `Failed(PROVIDER_ERROR)` (`:180-182`). | `HttpTransactionProviderTest.java:135` (504 → Unknown, 1 petición), `:307` (504 abre el circuito), `:120` (500/502/503 → Failed) |
| 5 | Un 5xx con cuerpo de rechazo se trataba como falla técnica | `HttpTransactionProvider.java:167-169`: **cualquier** 4xx o 5xx con `code` y `message` → `Rejected(code, message)` (RN06). No cuenta para el circuito (`recordResult` solo marca `Failed`/`Unknown`, `:104`). | `HttpTransactionProviderTest.java:107` (400, 402, 409, 422, 500, 503, 504 con cuerpo → Rejected), `:293` (5 × 500 con cuerpo → circuito `CLOSED`) |
| 2 | `readTimeout ≤ connectTimeout` clasifica mal una conexión lenta | `ProviderProperties.java:29-32`: exige `read-timeout > connect-timeout`; el mensaje nombra ambas propiedades. | `provider/http/ProviderPropertiesTest.java:25` (igual, menor y muy menor se rechazan), `:35` (1.001 s > 1 s se acepta), `ProviderClientConfigurationTest.java:100` (el contexto no arranca y la causa raíz nombra `provider.read-timeout`) |
| 3 | El contrato "no lanza" solo cubría `RestClientException` | `HttpTransactionProvider.java:142-148`: cualquier otra `RuntimeException` dentro de `send` → `Unknown(PROVIDER_UNEXPECTED_ERROR)` con `log.error` (incluye el stack para diagnóstico). No es reintentable y cuenta para el circuito como un solo resultado fallido. | `HttpTransactionProviderTest.java:178`: un interceptor del builder lanza `IllegalStateException` → `Unknown(PROVIDER_UNEXPECTED_ERROR)` y `getNumberOfFailedCalls() == 1` (sin reintentos) |
| 4 (parcial) | La regla `@Transactional` solo veía anotaciones directas | `architecture/ArchitectureTest.java:150-153`: `isAnnotatedWith(name) \|\| isMetaAnnotatedWith(name)` para Spring y Jakarta `@Transactional`, en la clase y en los métodos. **Fuera de alcance (feature 4):** cubrir llamadores indirectos (controller/facade que llama al servicio que usa el puerto); queda para que el leader lo anote en `history.md`. | `ArchitectureTest.java:96` con los fixtures `fixture/transaction/TransactionalOperation.java` (anotación propia meta-anotada con `@Transactional`) y `MetaTransactionalProviderCallerFixture.java`; `:84` sigue cubriendo la anotación directa en un método |
| 6 | `RestClient.builder()` estático ignoraba la configuración de Boot | `pom.xml:39`: `spring-boot-starter-restclient` (ver Dependencias). `ProviderClientConfiguration.java:15-16` inyecta el `RestClient.Builder` de Boot; `HttpTransactionProvider.java:67`, `:76` lo **clona** antes de fijar `baseUrl` y la request factory. | `ProviderClientConfigurationTest.java:83`: un `RestClientCustomizer` registrado en el contexto agrega un header que WireMock recibe, lo que prueba que se usa el builder de Boot |
| 7 | APPROVED sin `executedAt` daba `Approved` con `null` | `ExecuteResponse.java:23`: `isApproved()` exige además `executedAt != null`. | `HttpTransactionProviderTest.java:166` (4 cuerpos: sin APPROVED, sin `executedAt`, sin `transactionId`, sin `balance` → `Unknown(PROVIDER_INVALID_RESPONSE)`) |
| 8 | Los primitivos de retry/CB caían a 0 con un error críptico | Validación en los constructores compactos de `ProviderProperties.java` (`:21-32`, `:46-58`, `:71-87`): `max-attempts ≥ 1`, `wait-duration > 0`, `backoff-multiplier ≥ 1`, `randomization-factor ∈ [0,1)`, `failure-rate-threshold ∈ (0,100]`, `sliding-window-size ≥ 1`, `minimum-number-of-calls ≥ 1`, duraciones > 0. Cada mensaje nombra la propiedad. Se eligió el constructor compacto en vez de `@Validated` + Bean Validation: el record se valida igual al construirse en tests sin Spring. | `ProviderPropertiesTest.java:42-112` |
| 9 | El log de "inalcanzable" reportaba los intentos configurados, no los reales | `HttpTransactionProvider.java:117`: "tras agotar los reintentos: {causa raíz}", sin número. | Cubierto por `connectionRefusedIsRetriedThenFailed` (`HttpTransactionProviderTest.java:211`); el número real de intentos lo afirma `getNumberOfFailedCalls() == 3` |
| 10 | Espera fija entre reintentos, sin backoff ni jitter | `HttpTransactionProvider.java:87-88`: `IntervalFunction.ofExponentialRandomBackoff(wait-duration, backoff-multiplier, randomization-factor)`. Nuevas propiedades `provider.retry.backoff-multiplier` (por defecto 2) y `provider.retry.randomization-factor` (por defecto 0.5) en `application.yaml`. Accessor package-private `retry()` (`:233`) para tests. | `HttpTransactionProviderTest.java:237` (sin jitter: 100 → 200 ms), `:251` (con jitter 0.5: 50–150 y 100–300 ms en 50 muestras), `ProviderClientConfigurationTest.java:48` (valores por defecto enlazados) |

## Dependencias (vuelta 2)

- **`org.springframework.boot:spring-boot-starter-restclient`** (BOM 4.1.2). Trae solo `spring-boot-restclient` y `spring-boot-http-client`. Es el módulo de Boot 4 que publica el bean `RestClient.Builder` (con los converters de Jackson de Boot, customizers y observabilidad), necesario para el ítem 6. No agrega tecnología fuera del stack: `RestClient` ya es el cliente HTTP fijado en `.claude/CLAUDE.md`. Por eso **no se escribió ADR**. **Para el leader:** si consideras que un starter nuevo exige ADR, va en la feature 7 o lo decides tú. El módulo también autoconfigura un `RestTemplateBuilder`; no se usa en ningún lado (RestTemplate sigue prohibido).

## Tabla excepción/estado → resultado → retry → circuito (actualizada)

| Situación | Lo que observa el adaptador | Resultado del puerto | ¿Retry? | ¿Falla para el circuito? |
|---|---|---|---|---|
| 200 con `status: APPROVED`, `transactionId`, `balance` **y `executedAt`** | respuesta | `Approved(transactionId, balance, executedAt)` | No | No |
| 200 sin aprobación completa | respuesta | `Unknown(PROVIDER_INVALID_RESPONSE)` | No | Sí |
| **4xx o 5xx** con `code` y `message` | respuesta | `Rejected(code, message)` del proveedor (RN06) | No | **No** |
| 4xx sin cuerpo de error | respuesta | `Failed(PROVIDER_INVALID_RESPONSE)` | No | Sí |
| 500 / 502 / 503 (y otros 5xx distintos de 504) sin cuerpo | respuesta | `Failed(PROVIDER_ERROR)` | No | Sí |
| **504 sin cuerpo** | respuesta | `Unknown(PROVIDER_GATEWAY_TIMEOUT)` | No | Sí |
| Conexión rechazada / timeout de conexión / host desconocido / sin ruta | `ResourceAccessException` ← `ConnectException`, `HttpConnectTimeoutException`, `UnknownHostException`, `NoRouteToHostException` | `Failed(PROVIDER_UNAVAILABLE)` tras agotar los intentos | **Sí** (backoff exponencial con jitter) | Sí (cada intento) |
| Timeout de lectura | `ResourceAccessException` ← `HttpTimeoutException` | `Unknown(PROVIDER_TIMEOUT)` | No | Sí |
| Conexión cortada a mitad | `ResourceAccessException` ← `IOException` | `Unknown(PROVIDER_COMMUNICATION_ERROR)` | No | Sí |
| Otra `RestClientException` | excepción | `Unknown(PROVIDER_COMMUNICATION_ERROR)` | No | Sí |
| **Cualquier otra `RuntimeException`** | excepción | `Unknown(PROVIDER_UNEXPECTED_ERROR)` + `log.error` | No | Sí |
| Circuito abierto | `CallNotPermittedException` | `Failed(PROVIDER_CIRCUIT_OPEN)`, sin petición HTTP | No | n/a |
| Configuración inválida (p. ej. `read-timeout ≤ connect-timeout`) | `IllegalArgumentException` al enlazar `provider.*` | La app no arranca (mensaje con el nombre de la propiedad) | n/a | n/a |

## Pre-matriz: filas nuevas o cambiadas

| Regla | Qué exige | Implementación (`archivo:línea`) | Test que la afirma (`archivo:línea`) | Verificación manual |
|-------|-----------|----------------------------------|--------------------------------------|---------------------|
| RN06-5xx-con-cuerpo | 5xx con cuerpo → `code` y `message` del proveedor, como rechazo | `HttpTransactionProvider.java:167-169` | `HttpTransactionProviderTest.java:107`, `:293` | N/A (sin endpoint hasta la feature 5) |
| RN06-5xx-sin-cuerpo | 500/502/503 sin cuerpo → falla | `HttpTransactionProvider.java:180-182` | `HttpTransactionProviderTest.java:120` | N/A |
| B4-504 | 504 sin cuerpo → resultado incierto | `HttpTransactionProvider.java:175-178` | `HttpTransactionProviderTest.java:135`, `:307` | N/A |
| RN05-executedAt | Éxito literal incluye `executedAt` | `ExecuteResponse.java:23` | `HttpTransactionProviderTest.java:166` | N/A |
| A5-no-lanza | El puerto no lanza ante ninguna `RuntimeException` | `HttpTransactionProvider.java:142-148` | `HttpTransactionProviderTest.java:178` | N/A |
| D2-backoff | Reintentos con backoff exponencial y jitter | `HttpTransactionProvider.java:87-88` | `HttpTransactionProviderTest.java:237`, `:251` | N/A |
| Config-validación | Configuración inválida falla al arrancar nombrando la propiedad | `ProviderProperties.java:21-32`, `:46-58`, `:71-87` | `ProviderPropertiesTest.java:25-112`, `ProviderClientConfigurationTest.java:100` | N/A |
| Builder-Boot | El adaptador usa el `RestClient.Builder` de Boot | `ProviderClientConfiguration.java:15-16`, `HttpTransactionProvider.java:76` | `ProviderClientConfigurationTest.java:83` | N/A |
| C3-meta | `@Transactional` como meta-anotación también se detecta | `ArchitectureTest.java:150-153` | `ArchitectureTest.java:96` | N/A |
| RN05/RN06-persistencia | Se persiste | — | — | **BLOCKED**: feature 4 (sin cambios respecto a la vuelta 1) |

## Auditoría L3: filas que cambian

| Regla | Estado | Evidencia | Nota |
|-------|--------|-----------|------|
| A5 | ✅ | `HttpTransactionProvider.java:142-148` | Ahora cubre toda `RuntimeException` dentro de `send`. |
| B4 | ✅ | `HttpTransactionProvider.java:175-178`; `ExecuteResponse.java:23` | 504 y una aprobación incompleta → `Unknown`. |
| D2 | ✅ | `HttpTransactionProvider.java:87-90` | Solo conexión, con backoff exponencial y jitter. |
| D3 | ✅ | `HttpTransactionProvider.java:104`, `:167-169` | Los 4xx/5xx con cuerpo de rechazo no cuentan para el circuito. |
| F2 | ✅ | `ProviderProperties.java:21-87` | Record tipado y validado al arrancar. |
| G5 | ✅ | `ArchitectureTest.java:150-153` | Incluye meta-anotaciones. |
| H4 | ✅ | `HttpTransactionProvider.java:143-144`, `:168`, `:176`; `ProviderProperties.java:27-28` | Comentarios solo para el por qué. |

## Commit sugerido (vuelta 2, si se separa)

```
fix(provider): clasifica 5xx con cuerpo como rechazo, 504 como incierto y valida la configuración
```
