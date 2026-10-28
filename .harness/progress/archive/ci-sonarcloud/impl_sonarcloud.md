# impl_sonarcloud — Integración SonarCloud en CI

Rama: `ci/sonarcloud` (sin commit). Tarea de CI, no es feature de `feature_list.json`.

## Archivos modificados

- `pom.xml` — en `<properties>`, tras `<jacoco.version>`:
  ```xml
  <sonar.host.url>https://sonarcloud.io</sonar.host.url>
  <sonar.organization>chilcho1939</sonar.organization>
  <sonar.projectKey>chilcho1939_api-bank-harness</sonar.projectKey>
  <sonar.coverage.jacoco.xmlReportPaths>${project.build.directory}/site/jacoco/jacoco.xml</sonar.coverage.jacoco.xmlReportPaths>
  ```
- `.github/workflows/ci.yml`
  - Job `verify`: `env: SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}` a nivel job.
  - Se reemplazó el comentario final sobre Sonar por el step `Análisis en SonarCloud` (después de `Verify`), con `if: env.SONAR_TOKEN != ''`, `env.GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}` y `run: ./mvnw -B org.sonarsource.scanner.maven:sonar-maven-plugin:sonar`.

Sin cambios en `src/`, sin exclusiones de Sonar, sin dependencias nuevas.

## Ruta JaCoCo

La ejecución `report-merged` de `jacoco-maven-plugin` escribe en `${project.reporting.outputDirectory}/jacoco` (pom.xml:210). El pom no sobreescribe `<reporting><outputDirectory>`, así que vale el default de Maven `${project.build.directory}/site` → `target/site/jacoco/jacoco.xml`, igual a `sonar.coverage.jacoco.xmlReportPaths`. Confirmado: tras `verify` existe `target/site/jacoco/jacoco.xml` (10994 bytes).

## Resultado `./mvnw -B verify`

```
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0   (surefire, unitarios + ArchUnit)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0    (failsafe, ApplicationContextIT)
[INFO] BUILD SUCCESS
```

## Notas

- El step de Sonar no se ejecutó localmente (requiere `SONAR_TOKEN`); se validará en el primer run de GitHub Actions. El usuario debe crear el secreto `SONAR_TOKEN` en el repo y desactivar "Automatic Analysis" en SonarCloud (si no, el análisis por CI falla).
