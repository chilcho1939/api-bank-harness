# Verificación — Cómo demostrar que el trabajo funciona

> Regla de oro: **el agente no dice "funciona", lo demuestra.**

## Niveles

### Nivel 0 — Compila (automático)

El hook `PostToolUse` corre `./mvnw -q test-compile` al editar un `.java` o el `pom.xml`. Rojo = arreglar antes del siguiente cambio.

### Nivel 1 — Unitarios (automático al parar)

El hook `Stop` corre `./mvnw -q test` (solo `*Test`, sin Docker).

Cada feature deja tests que:

1. Cubren el camino feliz.
2. Cubren cada regla de su lista `rules`, incluidos los valores frontera.
3. Cubren al menos un camino de error.

### Nivel 2 — Suite completa (obligatoria al cerrar)

```bash
./.harness/init.sh     # incluye ./mvnw verify: unitarios + *IT con Testcontainers + ArchUnit
```

Requiere Docker en ejecución.

### Nivel 3 — Smoke HTTP (features 5–7)

```bash
docker compose up -d --build
curl -s localhost:8080/actuator/health                     # {"status":"UP"}

curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Transferencia recibida"}'

curl -s localhost:8089/__admin/requests | head            # llamadas que recibió el proveedor

docker compose down
```

Un `curl` por regla, incluidos negativos y bordes. Se copia comando, status y cuerpo al reporte.

## Antes de cerrar

`./.harness/init.sh` en verde. Si está rojo, nada se marca `done`: se anota el bloqueo en `current.md` y la feature pasa a `blocked`.

## Anti-patrones

- ❌ "Agregué la clase, debería funcionar." → falta el test.
- ❌ Test que solo verifica que no lanza.
- ❌ Mockear `RestClient` en lugar de usar WireMock.
- ❌ H2 en lugar de Testcontainers.
- ❌ Marcar `done` sin `./.harness/init.sh` en verde.
- ❌ `@Disabled` para que la suite pase.
