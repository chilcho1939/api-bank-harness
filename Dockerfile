# syntax=docker/dockerfile:1

# ── Etapa 1: compila el jar y lo separa en capas ────────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Wrapper y pom primero: esta capa solo se invalida si cambian las dependencias.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# Los tests no corren aquí: necesitan Docker (Testcontainers) y los ejecuta CI con ./mvnw verify.
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests -Djacoco.skip=true \
    && cp target/transactions-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ── Etapa 2: runtime con JRE y usuario sin privilegios ──────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system --gid 10001 spring \
    && useradd --system --uid 10001 --gid spring --no-create-home --shell /usr/sbin/nologin spring

# De la capa que menos cambia a la que más: un cambio de código solo reconstruye la última.
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./

USER spring:spring
EXPOSE 8080

# El heap se ajusta al límite de memoria del contenedor, no al del host.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "application.jar"]
