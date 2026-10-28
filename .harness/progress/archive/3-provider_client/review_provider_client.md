# Review — feature 3 provider_client (vuelta 2)

**Veredicto:** APPROVED

**Cobertura:** 26/26 verificados: los 16 de la vuelta 1, revalidados, más los 10 ítems "Corregir" del triage `/code-review`.

Rutas relativas a `src/main/java/mx/gilortiz/transactions/` (main) y `src/test/java/mx/gilortiz/transactions/` (test).

## Verificación ejecutable (propia)

- `./mvnw verify` → Surefire `Tests run: 89, Failures: 0, Errors: 0, Skipped: 0`; Failsafe `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
  - Por suite: `contrato y traducción de respuestas` 19, `timeouts y reintentos` 5, `circuit breaker` 6 (HttpTransactionProviderTest = 30); `ProviderPropertiesTest` 18; `ProviderClientConfigurationTest` 4; `ArchitectureTest` 7; `TransactionRulesTest` 30; `ApplicationContextIT` 3.
- `./.harness/init.sh` → exit 0 (`verify verde`, `Entorno listo.`). La línea `ERROR … Error no previsto al llamar al proveedor` que aparece en la salida es el `log.error` esperado de `unexpectedRuntimeExceptionIsUnknown`, no una falla.
- Flakiness: corrí 3 veces seguidas `./mvnw -q test -Dtest='HttpTransactionProviderTest*,ProviderClientConfigurationTest,ProviderPropertiesTest'` y las 3 terminaron con exit 0, sin FAIL. La suite más lenta (`circuit breaker`) tarda 0.6–1.7 s y `HttpTransactionProviderTest` completo unos 3.2 s. Los tests de backoff leen la `IntervalFunction` y no esperan en tiempo real.
- Dependencia: `git diff main -- pom.xml` agrega solo `spring-boot-starter-restclient`. `dependency:tree` muestra `spring-boot-starter-restclient:4.1.2` → `spring-boot-restclient:4.1.2` → `spring-boot-http-client:4.1.2` y nada más. Según la decisión del leader, no requiere ADR.
- Smoke `curl`: N/A. La feature no expone HTTP (features 5–7).
- `@Disabled`: no aparece en `src/test`.

## Matriz — ítems "Corregir" del `/code-review`

| # | Qué exige | Cómo lo probé | Evidencia | Resultado |
|---|---|---|---|---|
| 1 | 504 sin cuerpo → `Unknown`, sin retry y cuenta para el CB; 500/502/503 sin cuerpo → `Failed` | `provider/http/HttpTransactionProvider.java:175-182`; tests `provider/http/HttpTransactionProviderTest.java` `gatewayTimeoutWithoutBodyIsUnknown`, `serverErrorWithoutBodyIsFailed` (500/502/503) y `gatewayTimeoutsOpenCircuit` | 504 → `Unknown(PROVIDER_GATEWAY_TIMEOUT)` + `verify(1, …)`. 500/502/503 → `Failed(PROVIDER_ERROR)` + `verify(1, …)`. 2 × 504 → `State.OPEN`. Todo pasa. | PASS |
| 5 | 4xx o 5xx con cuerpo `code`/`message` → `Rejected` sin contar para el CB (RN06) | `HttpTransactionProvider.java:167-169` y `:104-105` (`recordResult` solo `Failed`/`Unknown`); tests `errorWithBodyIsRejected` (400, 402, 409, 422, 500, 503, 504) y `rejectionsDoNotOpenCircuit` (422, 500) | `isEqualTo(Rejected("INSUFFICIENT_FUNDS", …))` + `verify(1)`. Con 5 × 500 con cuerpo y un CB que abre tras 2 fallas, el estado sigue `CLOSED` con `verify(5, …)`. | PASS |
| 2 | `readTimeout > connectTimeout`, validado al arrancar | `provider/http/ProviderProperties.java:29-32`; `ProviderPropertiesTest.rejectsReadTimeoutNotGreaterThanConnectTimeout` (1000/1000, 1000/999, 3000/1000) y `acceptsReadTimeoutJustAboveConnectTimeout`; `ProviderClientConfigurationTest.failsToStartWhenReadTimeoutNotGreaterThanConnectTimeout` | Lanza `IllegalArgumentException` y el mensaje nombra `provider.read-timeout` y `provider.connect-timeout`. Con 3s/3s el contexto falla y la causa raíz nombra la propiedad. | PASS |
| 3 | Cualquier `RuntimeException` → `Unknown`, sin retry | `HttpTransactionProvider.java:142-148`; test `unexpectedRuntimeExceptionIsUnknown` (un interceptor lanza `IllegalStateException`) | `Unknown(PROVIDER_UNEXPECTED_ERROR)` y `getNumberOfFailedCalls() == 1`: un solo intento, sin retry. `ProviderUnreachableException` se lanza desde el catch de `:136-137`, así que no la atrapa el catch de `:142`. Es semántica de Java y está bien. | PASS |
| 4 (parcial) | ArchUnit cubre meta-anotaciones de `@Transactional` | `architecture/ArchitectureTest.java` `isTransactional` (`isAnnotatedWith \|\| isMetaAnnotatedWith`, Spring y Jakarta); test `transactionalRuleDetectsMetaAnnotation` con los fixtures `TransactionalOperation` y `MetaTransactionalProviderCallerFixture` | Se detecta la violación y el detalle contiene `MetaTransactionalProviderCallerFixture está anotada con @Transactional`. La regla directa sigue cubierta por `transactionalRuleDetectsViolation`. La cobertura de llamadores indirectos queda para la feature 4, como decidió el leader. | PASS |
| 6 | Usar el `RestClient.Builder` de Boot, clonado | `provider/http/ProviderClientConfiguration.java:15-16`; `HttpTransactionProvider.java:76` (`restClientBuilder.clone()`); test `ProviderClientConfigurationTest.usesBootRestClientBuilder` | Un `RestClientCustomizer` del contexto agrega `X-Boot-Customizer: applied`, y WireMock lo recibe (`verify(1, …withHeader(...))`). | PASS |
| 7 | APPROVED sin `executedAt` → `Unknown(PROVIDER_INVALID_RESPONSE)` | `provider/http/ExecuteResponse.java:23`; test `successWithoutCompleteApprovalIsUnknown` con 4 cuerpos (sin APPROVED, sin `executedAt`, sin `transactionId`, sin `balance`) | Los 4 dan `Unknown(PROVIDER_INVALID_RESPONSE)`. | PASS |
| 8 | Validación de properties con mensaje que nombra la propiedad | `ProviderProperties.java:21-33`, `:46-59`, `:71-87`, `:90-95`; `ProviderPropertiesTest` (18 casos) | Cada caso afirma el tipo de excepción y `hasMessageContaining("provider.<propiedad>")`. Validar en el constructor compacto en vez de usar `@Validated` está justificado en el reporte. | PASS |
| 9 | El log no reporta los intentos configurados como si fueran reales | `HttpTransactionProvider.java:117` | `"Proveedor inalcanzable tras agotar los reintentos: {}"`, sin número. | PASS |
| 10 | Backoff exponencial con jitter, configurable | `HttpTransactionProvider.java:87-88` (`IntervalFunction.ofExponentialRandomBackoff`); `application.yaml` `provider.retry.backoff-multiplier`/`randomization-factor`; tests `retryWaitGrowsExponentially`, `retryWaitHasJitterWithinBounds` y `bindsDefaultsFromApplicationYaml` | Sin jitter: 100 → 200 ms. Con jitter 0.5, en 50 muestras: [50,150] y [100,300]. Defaults enlazados: `Retry(3, 200ms, 2, 0.5)`. | PASS |

## Matriz — acceptance originales (revalidados)

| Acceptance | Evidencia en la vuelta 2 | Resultado |
|---|---|---|
| Contrato literal `POST /provider/v1/execute` | `sendsLiteralContract`: WireMock `equalToJson` con el request de spec.md:69, `"amount":1500.00` y sin `description`. Pasa. | PASS |
| 200 APPROVED → aprobado con `transactionId` y `balance` | `approvedResponse`: `txn-789`, `5500.00` (`isEqualByComparingTo`) y `executedAt`. Pasa. | PASS |
| 4xx con cuerpo → rechazado con `code`/`message` | `errorWithBodyIsRejected` con 400/402/409/422. Pasa. | PASS |
| 5xx sin cuerpo → falla; timeout de lectura → desconocido | 500/502/503 → `Failed(PROVIDER_ERROR)`; `readTimeoutIsUnknownWithoutRetry` → `Unknown(PROVIDER_TIMEOUT)`. 504 sin cuerpo pasa a `Unknown`: es una desviación deliberada del texto del acceptance, decidida por el leader en el triage #1 y respaldada por B4. Ver nota 1. | PASS |
| Conexión rechazada → 3 intentos → falla; timeout de lectura NO se reintenta | `connectionRefusedIsRetriedThenFailed`: `Failed(PROVIDER_UNAVAILABLE)` + `getNumberOfFailedCalls() == 3` (el retry envuelve al CB, `:112-113`). `readTimeoutIsUnknownWithoutRetry`: `verify(1, …)` con `maxAttempts = 3`. | PASS |
| CB abierto no llama al proveedor; los rechazos de negocio no lo abren | `openCircuitDoesNotCallProvider`: `OPEN`, `resetRequests()`, `Failed(PROVIDER_CIRCUIT_OPEN)` + `verify(0, …)`. `rejectionsDoNotOpenCircuit`: `CLOSED` + `verify(5, …)`. | PASS |
| Timeouts y base-url con `@ConfigurationProperties` | `ProviderProperties.java:13`; `bindsDefaultsFromApplicationYaml` (8089/1s/3s); `appliesConfiguredBaseUrlAndReadTimeout` (con override, `Unknown(PROVIDER_TIMEOUT)` y 1 petición). | PASS |
| Tests con WireMock para cada rama | 30 casos con `WireMockExtension` real, sin mocks de `RestClient`. | PASS |
| A5 / D4 / D5 / ArchUnit / alcance | `ProviderResult` sellado; Resilience4j programático (`:84-108`); pom sin starter ni anotaciones de Resilience4j; `ArchitectureTest` 7/7; el dominio no importa `provider.http`; no hay entidad, servicio ni endpoints. | PASS |

## Estándar Java L3 (filas verificadas por mí)

| Regla | Evidencia | Resultado |
|---|---|---|
| A5 | `transaction/ProviderResult.java:10` sellado; `HttpTransactionProvider.java:114-124` y `:136-148` cubren toda `RuntimeException` | PASS |
| B4 | Timeout (`:192-195`), 504 (`:175-178`), aprobación incompleta (`:161-165`), E/S a mitad (`:197-200`) y error no previsto (`:146`) → `Unknown` | PASS |
| D1 | `HttpTransactionProvider.java:71` (`connectTimeout`), `:74` (`setReadTimeout`); invariante `read > connect` en `ProviderProperties.java:29` | PASS |
| D2 | `:89` `retryExceptions(ProviderUnreachableException.class)` es lo único reintentable; `:87-88` backoff exponencial con jitter | PASS |
| D3 | `:112-113` Retry(CB(send)); `:104-105` `Rejected` no se registra | PASS |
| D5 | `:84-108` configuración en código; pom solo con `resilience4j-retry`/`-circuitbreaker` | PASS |
| F2 | `ProviderProperties.java:13-19` `record` + `@ConfigurationProperties("provider")`, validado | PASS |
| H2 | `HttpTransactionProvider.java:51-54` campos `private final`; `ProviderClientConfiguration.java:15` inyección por parámetro | PASS |

Las filas que cambian en la tabla de auditoría del implementer (vuelta 2) coinciden con lo que observé.

## Checkpoints

- C1: [x] El arnés está completo y `init.sh` termina con exit 0.
- C2: [x] Una sola feature `in_progress` (3); `current.md` describe la sesión activa; la entrada de `history.md` corresponde al cierre del leader.
- C3: [x] Paquetes `transaction` / `provider/http`. ArchUnit verde, incluidas las meta-anotaciones. Sin dependencias prohibidas: el starter de restclient autoconfigura un `RestTemplateBuilder`, pero no se usa en ningún lado (grep `RestTemplate` vacío en `src/main`). Sin `System.out` ni `@Autowired` en campos de `src/main`. La dependencia nueva no lleva ADR, por decisión explícita del leader.
- C4: [x] `BUILD SUCCESS` con 92 tests y sin `@Disabled`. Cada regla tiene una aserción concreta (tipo, código, conteo de WireMock o estado/métricas del CB). Proveedor probado con WireMock.
- C5: [x] Sin artefactos de build trackeados. Los reportes `impl_`/`review_`/`review_*_codereview` los archiva el leader.

## Cambios requeridos

Ninguno.

## Notas (no bloqueantes)

1. Coherencia documental, a cargo del leader. Ahora un 5xx con cuerpo → `REJECTED` y un 504 sin cuerpo → `UNKNOWN`, pero `docs/architecture.md:70` sigue diciendo `PENDING --> FAILED: … 5xx` y el acceptance de `feature_list.json` dice "5xx sin cuerpo → resultado de falla". Conviene actualizar ambos textos para reflejar las decisiones del triage #1 y #5.
2. Un 504 **con** cuerpo `code`/`message` se clasifica como `Rejected` (está en `errorWithBodyIsRejected`). Es coherente con RN06, pero si un gateway intermedio llegara a devolver un cuerpo con esa forma, se perdería la incertidumbre. Hoy es un riesgo teórico.
3. No hay un test que ejecute el timeout de conexión ni el host desconocido (`HttpConnectTimeoutException`/`UnknownHostException`). El orden de la clasificación es correcto en el código (`:205` antes de `:192`) y el invariante `read > connect` evita la ambigüedad.
4. La cobertura de `@Transactional` en llamadores indirectos (controller/facade) queda pendiente para la feature 4, como se acordó.
5. Compose (feature 7): el servicio `app` debe definir `PROVIDER_BASE_URL=http://provider-mock:8080`.
6. Siguen sin trackear, fuera del alcance de la feature: `.harness/git-hooks/commit-msg` y `docs/sonar-decisiones.md`. No conviene mezclarlos en el commit de la feature 3.
