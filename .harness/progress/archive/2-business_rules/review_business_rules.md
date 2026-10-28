# Review — feature 2 business_rules

**Veredicto:** APPROVED

**Cobertura:** 16/16 reglas verificadas.

## Verificación ejecutada por mí

- `./mvnw -B clean verify` → exit 0:
  - `Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 -- in mx.gilortiz.transactions.transaction.TransactionRulesTest`
  - `Tests run: 4 ... ArchitectureTest`; Surefire total `Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`
  - Failsafe `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` (`ApplicationContextIT`)
  - `jacoco:merge` → `Loading execution data file .../target/jacoco.exec` + `Loading execution data file .../target/jacoco-it.exec` → `jacoco-merged.exec`; `jacoco:report (report-merged)` → `Analyzed bundle 'transactions' with 5 classes`
  - `BUILD SUCCESS`
- `./.harness/init.sh` → `verify verde (unitarios + integración + ArchUnit)`, `Entorno listo.`, `init_exit=0`.
- Smoke HTTP (`curl`): no aplica; la feature no expone HTTP (endpoints son feature 5). RN04 no pertenece a esta feature.

## Matriz de reglas

| Regla | Qué exige | Cómo lo probé | Evidencia | Resultado |
|-------|-----------|---------------|-----------|-----------|
| RN01 | `amount > 1.00` con `compareTo` | Lectura `TransactionRules.java:8,20` + test `TransactionRulesTest.java:21-34` (incluye `0.99`, `0.00`, `-5.00`) | 7 casos `rejectsAmountNotGreaterThanMinimum[1..7]` en verde; afirma `violation`, `code` y mensaje (`:107-114`) | PASS |
| RN01-borde | `1.00` se rechaza para CREDIT y DEBIT | `TransactionRulesTest.java:24-25` (+ `1`, `1.0` en `:26-27`, prueba de escala) | `AMOUNT_TOO_LOW` afirmado por `assertRejected` | PASS |
| RN01-borde | `1.01` se acepta para CREDIT y DEBIT | `TransactionRulesTest.java:36-41` (`@EnumSource(TransactionType.class)`) | `acceptsAmountJustAboveMinimum[1..2]` en verde | PASS |
| RN02 | DEBIT `≤ 10,000.00` | `TransactionRules.java:9,23` | `type == DEBIT && compareTo(10000.00) > 0` | PASS |
| RN02-borde | DEBIT `10000.00` se acepta | `TransactionRulesTest.java:43-48` (`10000.00`, `10000`, `9999.99`) | `acceptsDebitUpToLimit[1..3]` verde | PASS |
| RN02-borde | DEBIT `10000.01` se rechaza | `TransactionRulesTest.java:50-56` (`10000.01`, `10000.001`, `50000.00`) | `DEBIT_AMOUNT_EXCEEDS_LIMIT` afirmado | PASS |
| RN02-CREDIT | CREDIT `10000.01` se acepta (sin límite) | `TransactionRulesTest.java:58-63` | `acceptsCreditWithoutUpperLimit[1..3]` verde | PASS |
| RN03 | `MXN` se acepta | `TransactionRulesTest.java:65-70` (CREDIT y DEBIT) | verde | PASS |
| RN03 | Otra moneda se rechaza | `TransactionRulesTest.java:72-78` (`USD`, `EUR`, `mxn`, `MX`, `MXNN`, `""`); impl `TransactionRules.java:26` | `UNSUPPORTED_CURRENCY` afirmado | PASS |
| Acc. códigos | Código estable en inglés + mensaje descriptivo | `BusinessRuleViolation.java:7-9,17-19`; test `TransactionRulesTest.java:88-101` afirma literales `AMOUNT_TOO_LOW`/`DEBIT_AMOUNT_EXCEEDS_LIMIT`/`UNSUPPORTED_CURRENCY` y mensajes en español | verde (3 casos) | PASS |
| Acc. parametrizados | Tests unitarios parametrizados con fronteras | `@ParameterizedTest` en `TransactionRulesTest.java:21,36,43,50,58,65,72,88`; fronteras `1.00`, `1.01`, `10000.00`, `10000.01` presentes | 30 casos en surefire XML | PASS |
| Acc. CI | `ci.yml` push/PR a main, temurin 21, caché Maven, `./mvnw verify` | `.github/workflows/ci.yml:3-7` (push/pull_request `[main]`), `:21-25` (`temurin`, `'21'`, `cache: maven`), `:29` (`./mvnw -B verify`); `git ls-files -s mvnw` → `100755` (ejecutable en el runner) | lectura | PASS |
| Acc. JaCoCo | `target/site/jacoco/jacoco.xml` tras verify con unit + IT | `pom.xml:161-215`. Tras `clean verify`: `target/site/jacoco/jacoco.xml` (9724 B). Contraprueba: reporte solo con `jacoco-it.exec` → `TransactionsApplication` 1 línea cubierta y `TransactionRules` 0/13; reporte fusionado → `TransactionsApplication` 1 cubierta **y** `TransactionRules` 13/13, `BusinessRuleViolation` 9/9, `BusinessRuleException` 5/5. Ambos orígenes presentes en el XML. | ver arriba | PASS |
| Acc. ADR | ADR que justifica JaCoCo y CI | `docs/adr/0001-cobertura-jacoco-e-integracion-continua.md`: contexto, decisión, 6 alternativas, consecuencias (incluye aviso `@{argLine}`) | lectura | PASS |
| Alcance | Sin entidad, servicio, DTOs ni endpoints | `ls src/main/java/mx/gilortiz/transactions/transaction/` → solo `TransactionType`, `TransactionRules`, `BusinessRuleViolation`, `BusinessRuleException`; sin `dto/`, sin `@Entity`, sin controller | lectura | PASS |
| Arquitectura | Reglas sin dependencias web, paquete `transaction` | `TransactionRules.java:3-4` solo importa `java.math.BigDecimal` y `java.util.Objects`; ArchUnit `ArchitectureTest.java:24-27` verde | verify | PASS |

## Estándar Java L3 (filas verificadas por mí)

| Regla | Evidencia | Resultado |
|-------|-----------|-----------|
| B1 | `TransactionRules.java:15` recibe `(type, amount, currency)`; imports `:3-4` sin Spring/web | PASS |
| B2 | `TransactionRules.java:20,23` usan `compareTo`; constantes `new BigDecimal("1.00")` / `("10000.00")` (`:8-9`); tests con `1`, `1.0`, `10000`, `10000.001` | PASS |
| B5 | `BusinessRuleViolation.java:7-9` UPPER_SNAKE en inglés, mensajes en español; `code()` = `name()` (`:17-19`) | PASS |
| A6 | `TransactionRules.java:6` clase concreta `final`, sin interfaz | PASS |
| G6 | `TransactionRulesTest.java:24-25,40,45,52,60` | PASS |
| G7 | `@DisplayName` en español (`:22,37,44,51,59,66,73,81,89`); rechazos afirman `violation`/`code`/mensaje (`:109-114`). Aceptaciones usan `doesNotThrowAnyException` (`:104`): `validate` es `void`, así que "no lanza" es el resultado concreto, y cada aceptación tiene su contraparte de rechazo con código afirmado | PASS |
| H2 | `BusinessRuleException.java:5` campo `private final` | PASS |
| H3/H5/C3-prohibidos | `grep -rn "System.out\|printStackTrace\|@Autowired\|lombok\|TODO" src/main pom.xml` → sin resultados | PASS |
| Convención visibilidad | `TransactionRules` package-private (`:6`) | PASS |

Tabla de auditoría del implementer: filas revisadas coinciden; N/A justificados (features 3–5).

## Checkpoints

- C1: [x] archivos del arnés presentes (init.sh sección 2); `init.sh` exit 0.
- C2: [x] una sola feature `in_progress` (2); `current.md` describe la sesión activa. Entrada de `history.md` para feature 2: responsabilidad del leader al cerrar.
- C3: [x] paquete `transaction` según `docs/architecture.md`; ArchUnit verde; sin dependencias prohibidas; JaCoCo (plugin de build) con ADR-0001.
- C4: [x] `BUILD SUCCESS`, 37 tests, ningún `@Disabled`; bordes `1.00`, `1.01`, `10000.00`, `10000.01` y moneda ≠ MXN probados. WireMock: N/A en esta feature (sin proveedor).
- C5: [x] `target/` ignorado (`.gitignore`), sin `*.log`/`.DS_Store` sueltos. `impl_/review_` en `progress/`: el leader los archiva al cerrar; `history.md` y estado `done`: acción del leader.

## Cambios requeridos

Ninguno.

## Notas (no bloqueantes)

1. `.claude/CLAUDE.md` y `.harness/AGENTS.md` aparecen modificados en `git diff main` (flujo de PR); no los lista el implementer, son edits de docs del leader, permitidos por su rol. Inclúyelos en un commit `docs:`/`chore:` separado.
2. `.claude/CLAUDE.md` define el tipo `ci` en Conventional Commits; para `.github/workflows/ci.yml` conviene `ci:` en vez de `build:`. Sugerencia de commits (en español, sin `Co-Authored-By`):
   - `feat(transaction): agrega reglas de negocio RN01–RN03 con códigos de error estables`
   - `build: agrega cobertura JaCoCo combinada (unitarios + integración)` (pom.xml + ADR-0001)
   - `ci: agrega workflow de GitHub Actions con ./mvnw verify`
3. Comparación de moneda sensible a mayúsculas (`mxn` se rechaza): coherente con "Solo `MXN`" literal de la spec; si feature 5 decide normalizar, debe hacerlo antes de invocar las reglas.
4. Durante la revisión regeneré temporalmente `target/site/jacoco/jacoco.xml` solo con `jacoco-it.exec` como contraprueba; `init.sh` posterior lo restauró al reporte fusionado (verificado: `TransactionRules` 13/13 líneas cubiertas).
