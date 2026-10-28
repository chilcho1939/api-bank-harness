# ADR-0001: Medir cobertura con JaCoCo y verificar cada cambio con GitHub Actions

**Estado:** Aceptada

## Contexto

La spec valora "cobertura razonable del dominio" y pide al menos tests unitarios con el proveedor mockeado (RT02). Hasta ahora la suite (`./mvnw verify`: unitarios, `*IT` con Testcontainers y ArchUnit) solo corre en la máquina de quien la ejecuta: nada impide que un PR a `main` llegue con la suite roja, y no hay un número de cobertura que un analizador (SonarQube/SonarCloud) pueda leer.

Restricciones:

- Stack fijo en `.claude/CLAUDE.md`; agregar herramientas de build exige ADR.
- La cobertura debe incluir los `*IT` (Failsafe), no solo los unitarios: el flujo completo contra Postgres real y WireMock es donde vive buena parte del comportamiento.
- Sonar lo configura el usuario; el build solo debe dejar el reporte listo.

## Decisión

- **JaCoCo** (`jacoco-maven-plugin` 0.8.13, versión fijada en `pom.xml` porque Spring Boot no la gestiona):
  - `prepare-agent` instrumenta Surefire → `target/jacoco.exec`.
  - `prepare-agent-integration` instrumenta Failsafe → `target/jacoco-it.exec`.
  - `merge` en `post-integration-test` fusiona ambos en `target/jacoco-merged.exec`.
  - `report` en `verify` genera `target/site/jacoco/jacoco.xml` (y HTML), la ruta que consume `sonar.coverage.jacoco.xmlReportPaths`.
  - Sin umbral (`check`) por ahora: la cobertura se observa, no bloquea el build.
- **GitHub Actions** (`.github/workflows/ci.yml`):
  - Se dispara en `push` y `pull_request` a `main`.
  - `ubuntu-latest`, Java 21 Temurin con `actions/setup-java` y caché de Maven.
  - Ejecuta `./mvnw -B verify`: la misma verificación que `./.harness/init.sh`. El runner trae Docker, así que los `*IT` con Testcontainers corren sin configuración extra.
  - Queda marcado el lugar del step de Sonar; no se agrega plugin de Sonar al `pom.xml`.

## Alternativas consideradas

- **Solo cobertura de unitarios:** más simple, pero subestima la cobertura real y deja fuera el flujo HTTP + BD que prueban los `*IT`.
- **Un único `jacoco.exec` con `append` para ambos agentes:** evita el `merge`, pero mezcla los orígenes y depende del orden implícito de las fases; el `merge` explícito se lee mejor.
- **`report` + `report-integration` por separado:** dos XML que Sonar puede leer, pero duplica configuración y no da un número único de cobertura.
- **Umbral de cobertura con `jacoco:check`:** se pospone; con pocas features un umbral fijo es arbitrario.
- **Otro CI (GitLab CI, Jenkins):** el repositorio vive en GitHub; Actions no requiere infraestructura adicional.
- **Plugin `sonar-maven-plugin` en el `pom.xml`:** acopla el build a un servidor externo; se prefiere configurar Sonar en el workflow.

## Consecuencias

- Cada PR a `main` muestra si `./mvnw verify` pasa antes de mergear.
- `target/site/jacoco/jacoco.xml` existe tras cada `verify` con la cobertura combinada, listo para Sonar.
- El agente de JaCoCo agrega una sobrecarga menor al tiempo de los tests.
- Si en el futuro se configura `argLine` en Surefire/Failsafe, debe incluir `@{argLine}` para no perder el agente.
- La versión de JaCoCo se actualiza a mano (soporte de nuevas versiones de Java).
