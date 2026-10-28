# AGENTS.md — Mapa de navegación para agentes

> Punto de entrada para cualquier agente. Es un **mapa**, no una biblia: lee solo lo que necesites.

## 1. Antes de empezar (obligatorio)

1. Ejecuta `./.harness/init.sh`. Si falla, **para** y resuelve el entorno.
2. Lee `.harness/progress/current.md`.
3. Lee `.harness/feature_list.json` y toma **una** feature `pending`. Nunca más de una.

## 2. Mapa del repositorio

| Archivo / carpeta | Qué contiene | Cuándo leerlo |
|---|---|---|
| `.harness/docs/spec.md` | Contrato funcional: endpoints, payloads, reglas RN01–RN07, requerimientos RT01–RT05 | Siempre, antes de implementar o revisar |
| `.harness/feature_list.json` | Features con `acceptance`, `rules` y `status` | Siempre, al empezar |
| `.harness/progress/current.md` | Estado de la sesión actual | Siempre, al empezar |
| `.harness/progress/history.md` | Bitácora append-only | Si necesitas contexto histórico |
| `.harness/progress/archive/` | Reportes de subagentes por feature cerrada | Forense de una feature |
| `.harness/docs/reference-config.md` | Versiones, `application.yaml` y compose de partida | Feature 1 y al tocar config |
| `docs/architecture.md` | Arquitectura objetivo y paquetes | Antes de implementar |
| `.harness/docs/conventions.md` | Nombres, estructura, tests, anti-patrones | Antes de escribir código |
| `.harness/docs/verification.md` | Cómo demostrar que funciona | Antes de declarar `done` |
| `.claude/skills/java-l3-standard/SKILL.md` | Estándar de calidad con checklist | Implementer y reviewer, siempre |
| `.harness/CHECKPOINTS.md` | Criterios objetivos de estado final | Para autoevaluarte y para el reviewer |
| `docs/adr/` | Decisiones de arquitectura | Antes de cambiar arquitectura o dependencias |
| `.claude/CLAUDE.md` | Rol `leader`, stack fijo, prohibiciones | Lo carga Claude solo |
| `.claude/agents/` | `leader`, `implementer`, `reviewer`, `explorer` | Si orquestas |
| `.claude/settings.json` | Hooks y permisos | No editar sin razón |

## 3. Reglas duras

- **Una feature a la vez.**
- **No declares `done` sin verde.** `./.harness/init.sh` termina con exit 0 (`./mvnw verify`: unitarios + integración + ArchUnit). Los hooks solo dan feedback rápido (compilar al editar, unitarios al parar); la suite completa es del cierre.
- **La spec es literal.** Campos, paths y estados como en `spec.md`. Si algo no está en la spec, no se inventa: se documenta como decisión en un ADR.
- **Sin solución de referencia.** No se lee `../api-bank-challenge/` ni soluciones publicadas del reto. Configuración base permitida: `.harness/docs/reference-config.md`.
- **Documenta mientras trabajas** en `current.md`, no al final.
- **Deja el repo limpio** al cerrar (§5).

## 4. Cómo elegir una feature

```
1. Abre feature_list.json
2. Filtra status == "pending"
3. Toma la de menor id (las features dependen de las anteriores)
4. status → "in_progress"
5. Anota en current.md: feature, hora de inicio, plan
```

## 5. Cierre de sesión

1. `./.harness/init.sh` → verde.
2. Feature terminada y aprobada: `status: "done"`.
3. Resumen de `current.md` al final de `history.md`.
4. `current.md` vuelve a la plantilla.
5. `impl_*`, `review_*`, `explore_*` → `.harness/progress/archive/<id>-<name>/` con `git mv`. El `pre-commit` bloquea si quedan sueltos.
6. Sin archivos temporales, `System.out` ni TODOs sin contexto.

## 6. Si te bloqueas

- Relee la spec y la sección relevante de `docs/`.
- Si una herramienta no responde como esperas, **no inventes un workaround**: estado `blocked` en `current.md` y en la feature, y termina.
