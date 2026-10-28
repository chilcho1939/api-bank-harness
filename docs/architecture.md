# Arquitectura objetivo

> Describe **a dónde** se llega, no cómo está escrito el código. Los detalles de implementación los decide el implementer dentro de estas fronteras.

## Contenedores

```mermaid
flowchart LR
    client(["Cliente"])
    subgraph compose["docker compose"]
        api["app<br/>Spring Boot · :8080"]
        db[("postgres<br/>PostgreSQL 17 · :5432")]
        provider["provider-mock<br/>WireMock · :8089"]
    end
    client -->|"HTTP/JSON<br/>POST · GET /transactions"| api
    api -->|"JDBC (Hikari)"| db
    api -->|"HTTP/JSON<br/>POST /provider/v1/execute"| provider
```

## Paquetes y dependencias

```mermaid
flowchart TB
    web["common/web<br/>errores RFC 9457 · paginación"]
    tx["transaction<br/>controller · service · rules · entidad · repositorio · puerto"]
    dto["transaction/dto<br/>records HTTP"]
    http["provider/http<br/>adaptador RestClient + Resilience4j"]

    tx --> dto
    tx --> web
    web --> tx
    http -->|"implementa el puerto"| tx
```

- `provider/http` depende de `transaction`; **nunca al revés** (ArchUnit).
- `common/web` traduce las excepciones de `transaction` a HTTP; con una sola funcionalidad se acepta esa dependencia mutua.

## Flujo de `POST /transactions`

```mermaid
sequenceDiagram
    participant C as Cliente
    participant API as Controller
    participant S as Service
    participant DB as PostgreSQL
    participant P as Proveedor

    C->>API: POST /transactions (+ Idempotency-Key opcional)
    API->>S: comando validado (formato)
    S->>S: reglas de negocio (RN01–RN03)
    opt hay Idempotency-Key
        S->>DB: ¿existe la llave?
    end
    S->>DB: INSERT PENDING (transacción corta)
    Note over S,P: sin conexión de BD retenida
    S->>P: POST /provider/v1/execute (timeouts · retry · circuit breaker)
    P-->>S: resultado
    S->>DB: UPDATE estado final (transacción corta)
    S-->>API: transacción
    API-->>C: 201 + transacción
```

## Estados

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> EXECUTED: proveedor aprueba
    PENDING --> REJECTED: proveedor rechaza (4xx con cuerpo)
    PENDING --> FAILED: no se pudo ejecutar (conexión, 5xx, circuito abierto)
    PENDING --> UNKNOWN: timeout de lectura (resultado incierto)
```

`PENDING` que no avanza y `UNKNOWN` requieren conciliación con el proveedor (fuera de alcance; se documenta en el README).

## Decisiones

Cada decisión con alternativas vive en `docs/adr/` (feature 7).
