# impl — feature 2 `business_rules` (RN01, RN02, RN03) + CI/JaCoCo

## Archivos

Creados:
- `src/main/java/mx/gilortiz/transactions/transaction/TransactionType.java` — enum `CREDIT`, `DEBIT` (literal de spec).
- `src/main/java/mx/gilortiz/transactions/transaction/BusinessRuleViolation.java` — enum con código estable (= nombre de la constante) y mensaje en español.
- `src/main/java/mx/gilortiz/transactions/transaction/BusinessRuleException.java` — `RuntimeException` con `violation()` y `code()`.
- `src/main/java/mx/gilortiz/transactions/transaction/TransactionRules.java` — `validate(type, amount, currency)`.
- `src/test/java/mx/gilortiz/transactions/transaction/TransactionRulesTest.java` — 9 métodos, 30 casos parametrizados.
- `.github/workflows/ci.yml`
- `docs/adr/0001-cobertura-jacoco-e-integracion-continua.md`

Modificados:
- `pom.xml` — propiedad `jacoco.version` 0.8.13 y plugin `jacoco-maven-plugin` (líneas 25, 161–215).
- `.harness/progress/current.md` — plan de la sesión.

## Dependencias

- `org.jacoco:jacoco-maven-plugin:0.8.13` (plugin de build, no dependencia de runtime). Spring Boot 4.1.2 no gestiona su versión, por eso se fija. ADR-0001.
- Actions: `actions/checkout@v4`, `actions/setup-java@v4` (temurin 21, `cache: maven`).

## Códigos de error elegidos

| Regla | Código | Mensaje |
|---|---|---|
| RN01 | `AMOUNT_TOO_LOW` | El monto debe ser mayor a 1.00 |
| RN02 | `DEBIT_AMOUNT_EXCEEDS_LIMIT` | El monto de un DEBIT no puede ser mayor a 10,000.00 |
| RN03 | `UNSUPPORTED_CURRENCY` | La moneda debe ser MXN |

## Verificación

`./.harness/init.sh` → exit 0 ("Entorno listo."). `./mvnw -B verify`:

```
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 -- in mx.gilortiz.transactions.transaction.TransactionRulesTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in mx.gilortiz.transactions.architecture.ArchitectureTest
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.13:merge (merge-unit-and-integration) @ transactions ---
[INFO] --- jacoco:0.8.13:report (report-merged) @ transactions ---
[INFO] BUILD SUCCESS
```

Conteo: 34 unitarios (30 nuevos) + 3 IT = 37 tests.

JaCoCo: `target/site/jacoco/jacoco.xml` existe tras verify (también HTML). Evidencia de que el merge incluye los IT: `TransactionsApplication` aparece con línea cubierta (solo la carga el `ApplicationContextIT`). `TransactionRules`: 13/13 líneas, 8/8 ramas.

## Decisiones no obvias

- **Orden de evaluación RN01 → RN02 → RN03**; se lanza la primera que falla (test `reportsFirstViolationInRuleOrder`). Un solo código por respuesta simplifica el `ProblemDetail` de feature 5.
- **Excepción vs. resultado:** se lanza `BusinessRuleException` (no un tipo sellado). A5 exige tipos sellados solo para el puerto del proveedor; para las reglas, la excepción la traduce el `@RestControllerAdvice` (feature 5) a 422 con `code`.
- **Código = `name()` del enum** `BusinessRuleViolation`: estable por construcción, sin strings duplicados.
- **`TransactionRules` es package-private y no es bean de Spring**: no tiene estado ni dependencias; feature 4 decide si la anota con `@Component` o la instancia. No se crea interfaz (A6).
- **Moneda comparada con `equals` y sensible a mayúsculas:** la spec dice "solo `MXN`"; `mxn` se rechaza. Si se quisiera normalizar, sería decisión de feature 5 (formato).
- **`Objects.requireNonNull`** en los parámetros: los nulos son error de formato (Bean Validation, feature 5); aquí solo fallan rápido como error de programación, no como regla de negocio.
- **Mensajes en español** (conventions.md: mensajes al cliente en español); el `code` en inglés.
- **JaCoCo con `merge` explícito** (jacoco.exec + jacoco-it.exec → jacoco-merged.exec en `post-integration-test`) y `report` en `verify` con `outputDirectory` = `target/site/jacoco`. Sin `check` de umbral (ADR-0001). Sin plugin de Sonar; `ci.yml` deja el comentario del step.
- **`fetch-depth: 0`** en checkout: Sonar necesita historial para blame; inocuo si no se usa.
- Los tests de aceptación usan `doesNotThrowAnyException`: `validate` es `void` y "se acepta" significa exactamente "no lanza"; cada valor aceptado tiene su contraparte rechazada con código afirmado (G7).

## Tabla de auditoría Java L3

| Regla | Estado | Evidencia (`archivo:línea`) | Nota |
|-------|--------|------------------------------|------|
| A1 | ✅ | `src/main/java/mx/gilortiz/transactions/transaction/TransactionRules.java:1` | Todo en el paquete `transaction`. |
| A2 | N/A | — | Sin controller/service en esta feature. |
| A3 | N/A | — | Puerto del proveedor: feature 3. |
| A4 | ✅ | `src/test/java/mx/gilortiz/transactions/architecture/ArchitectureTest.java:23` | Las reglas no importan `provider.http`; ArchUnit verde. |
| A5 | N/A | — | Puerto: feature 3. |
| A6 | ✅ | `TransactionRules.java:6` | Clase concreta, sin interfaz. |
| A7 | N/A | — | Sin DTOs. |
| B1 | ✅ | `TransactionRules.java:15` | Recibe solo `type`, `amount`, `currency`; sin imports de Spring ni web. |
| B2 | ✅ | `TransactionRules.java:8-9,20,23` | `BigDecimal` + `compareTo`; test con `1`, `1.0`, `10000`, `10000.001` (`TransactionRulesTest.java:23-30,45,52`). |
| B3 | N/A | — | Entidad: feature 4. |
| B4 | N/A | — | Estados: feature 4. |
| B5 | ✅ | `BusinessRuleViolation.java:6-8`, `BusinessRuleException.java:17` | Códigos UPPER_SNAKE en inglés + mensaje en español; afirmado en `TransactionRulesTest.java:95`. |
| C1–C7 | N/A | — | Sin persistencia en esta feature. |
| D1–D5 | N/A | — | Proveedor: feature 3. |
| E1 | N/A | — | Las reglas quedan listas para invocarse desde el servicio (feature 4); formato es feature 5. |
| E2–E7 | N/A | — | API: feature 5. |
| F1–F3 | N/A | — | Sin config ni logs nuevos. |
| G1 | ✅ | `src/test/java/mx/gilortiz/transactions/transaction/TransactionRulesTest.java:14` | Unitario puro, Surefire. |
| G2–G4 | N/A | — | Adaptador/controller/flujo: features 3–5. |
| G5 | ✅ | `ArchitectureTest.java:23` | Sigue verde con el nuevo paquete. |
| G6 | ✅ | `TransactionRulesTest.java:23-24,39,45,52,59` | `1.00`, `1.01`, `10000.00`, `10000.01` explícitos. |
| G7 | ✅ | `TransactionRulesTest.java:22,37,44,51,59,66,73,81,89` | `@DisplayName` en español; se afirma tipo, `violation`, `code` y mensaje. |
| H1 | ✅ | `TransactionType.java:3`, `BusinessRuleViolation.java:6` | Enums; sin records necesarios aún. |
| H2 | ✅ | `BusinessRuleException.java:5` | Campo `final`; sin inyección. |
| H3 | ✅ | `pom.xml` | Sin Lombok. |
| H4 | ✅ | `TransactionRules.java:12-14`, `BusinessRuleViolation.java:3-5` | Solo Javadoc que explica orden y que el nombre es el código. |
| H5 | ✅ | — | Sin `System.out`, TODOs ni código muerto. |

## Pre-matriz de reglas

| Regla | Qué exige | Implementación (`archivo:línea`) | Test que la afirma (`archivo:línea`) | Verificación manual (`curl`) |
|-------|-----------|----------------------------------|--------------------------------------|------------------------------|
| RN01 | `amount > 1.00` | `TransactionRules.java:20` | `TransactionRulesTest.java:32,39` | BLOCKED: sin endpoint hasta feature 5 |
| RN01-borde | `1.00` exacto se rechaza (CREDIT y DEBIT) | `TransactionRules.java:20-21` | `TransactionRulesTest.java:24-25` (+ `1`, `1.0`) | BLOCKED: feature 5 |
| RN01-borde | `1.01` se acepta (CREDIT y DEBIT) | `TransactionRules.java:20` | `TransactionRulesTest.java:36-41` (`@EnumSource`) | BLOCKED: feature 5 |
| RN01-tipo | Aplica a CREDIT y DEBIT | `TransactionRules.java:20` (no depende de `type`) | `TransactionRulesTest.java:23-30` | BLOCKED: feature 5 |
| RN02 | DEBIT `≤ 10,000.00` | `TransactionRules.java:23` | `TransactionRulesTest.java:46,53` | BLOCKED: feature 5 |
| RN02-borde | DEBIT `10000.00` se acepta | `TransactionRules.java:23` | `TransactionRulesTest.java:45` | BLOCKED: feature 5 |
| RN02-borde | DEBIT `10000.01` se rechaza | `TransactionRules.java:23-24` | `TransactionRulesTest.java:52` | BLOCKED: feature 5 |
| RN02-CREDIT | CREDIT sin límite (`10000.01` se acepta) | `TransactionRules.java:23` (`type == DEBIT`) | `TransactionRulesTest.java:59-61` | BLOCKED: feature 5 |
| RN03 | Solo `MXN` se acepta | `TransactionRules.java:26` | `TransactionRulesTest.java:66-70` | BLOCKED: feature 5 |
| RN03 | Cualquier otra moneda se rechaza | `TransactionRules.java:26-27` | `TransactionRulesTest.java:73-78` (`USD`, `EUR`, `mxn`, `MX`, `MXNN`, `""`) | BLOCKED: feature 5 |
| B5/acceptance | Código estable en inglés + mensaje descriptivo | `BusinessRuleViolation.java:6-8`, `BusinessRuleException.java:7-8` | `TransactionRulesTest.java:89-101`, `:107-115` | BLOCKED: feature 5 (ProblemDetail) |
| acceptance | Tests parametrizados con frontera | — | `TransactionRulesTest.java:20-78` | N/A |
| acceptance | CI en push/PR a main, temurin 21, caché Maven, `./mvnw verify` | `.github/workflows/ci.yml:3-7,20-28` | N/A (se verifica al abrir el PR) | N/A |
| acceptance | JaCoCo XML en `target/site/jacoco/jacoco.xml` (unit + IT) | `pom.xml:161-215` | `ls target/site/jacoco/jacoco.xml` tras verify; log `merge` carga `jacoco.exec` y `jacoco-it.exec` | N/A |
| acceptance | ADR de JaCoCo y CI | `docs/adr/0001-cobertura-jacoco-e-integracion-continua.md` | N/A | N/A |

RN04 (orden: el proveedor no recibe la llamada) no es de esta feature: se afirma en feature 4 con el mock del puerto.

## Commits sugeridos

```
feat: reglas de negocio RN01–RN03 con códigos de error estables
build: cobertura con JaCoCo (unitarios + integración) y CI con GitHub Actions
```

Archivos por commit:
1. `src/main/java/mx/gilortiz/transactions/transaction/*`, `src/test/java/mx/gilortiz/transactions/transaction/TransactionRulesTest.java`
2. `pom.xml`, `.github/workflows/ci.yml`, `docs/adr/0001-cobertura-jacoco-e-integracion-continua.md`
