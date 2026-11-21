# Git Workflow
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documentos relacionados** | Roadmap & Release Plan, Definition of Done |

---

## 1. Propósito del documento

Definir el flujo de trabajo de control de versiones para un **desarrollador único**, preservando las prácticas de trunk-based development y feature flags ya fijadas para el proyecto (ver `roadmap-and-release-plan.md` §5), sin asumir un proceso de equipo que no aplica en este contexto.

## 2. Modelo de ramas

- **Trunk-based development**: `main` es la única rama de larga vida.
- Ramas de feature de vida corta (`feat/<módulo>-<descripción-breve>`), fusionadas a `main` en el mismo día o en pocos días — nunca ramas que acumulen múltiples features sin fusionar.
- No existen ramas de release, develop ni staging separadas del control de versiones; los ambientes (`roadmap-and-release-plan.md` §5) se gestionan por despliegue, no por rama.

## 3. Convención de commits

**Idioma (decisión del 14-nov-2026): todo lo relativo a Git se escribe en inglés** — mensajes de commit (asunto y cuerpo), títulos y descripciones de *pull requests*, nombres de ramas y etiquetas (*tags*), y comentarios de revisión de código. Es el estándar de la industria y de las herramientas que consumen ese historial (changelogs automáticos, GitHub, CI). Los comentarios **dentro del código** siguen la regla contraria: español (`coding-standards.md` §5). La documentación de `docs/` y la tesis permanecen en español.

Se adopta el formato de **Conventional Commits**:

```
<tipo>(<módulo>): <descripción breve en imperativo>

<cuerpo opcional explicando el porqué, no el qué>
```

| Tipo | Uso |
|---|---|
| `feat` | Nueva funcionalidad visible para el usuario o nuevo caso de uso |
| `fix` | Corrección de un defecto |
| `refactor` | Cambio de estructura interna sin alterar comportamiento observable |
| `test` | Adición o ajuste de pruebas sin cambio de lógica de producción |
| `docs` | Cambios en `docs/` |
| `chore` | Tareas de mantenimiento (dependencias, configuración de build) |

Ejemplo: `feat(matching): calcula desglose explicable por dimensión (BR-MATCH-02)`

## 4. Estrategia de feature flags

- Funcionalidad incompleta o en progreso se integra a `main` oculta tras un feature flag, nunca aislada en una rama de larga vida (`roadmap-and-release-plan.md` §5).
- El mecanismo concreto de feature flag (Spring profiles vs. librería dedicada) es una pregunta abierta compartida con `system-design-document.md` §9 — no se duplica aquí.
- Un flag se retira del código (y de la configuración) tan pronto la funcionalidad se estabiliza y se habilita para el 100% de la validación — un flag no es una forma de dejar código muerto permanentemente.

## 5. Proceso de PR / self-review para desarrollador único

Sin un segundo desarrollador que revise, el proceso de PR se mantiene igualmente porque **dispara CI** y **fuerza una revisión propia con distancia** antes de fusionar a `main`:

1. Rama de feature corta, con commits siguiendo la convención de la sección 3.
2. Apertura de PR (incluso siendo el único colaborador) — dispara el pipeline de GitHub Actions (build, pruebas, linter).
3. **Self-review obligatorio** antes de fusionar: revisar el diff completo como si fuera ajeno, contra el checklist de `definition-of-done.md`.
4. Fusión solo si CI pasa en verde y el checklist de DoD se cumple — no se fusiona con pruebas en rojo "para arreglar después".
5. Squash merge a `main`, manteniendo el mensaje de commit final alineado a la convención de la sección 3.

## 6. Qué NO se hace

- No se trabaja directamente sobre `main`.
- No se dejan ramas abiertas por más de unos pocos días sin fusionar o descartar explícitamente.
- No se hace `git commit --amend` ni `push --force` sobre historial ya compartido/fusionado en `main`.
- No se comitea código comentado ni TODOs sin dueño y razón (ver `coding-standards.md` §5).

## 7. Preguntas abiertas

- ~~¿Se requiere una plantilla de PR que incluya el checklist de `definition-of-done.md`?~~ **Resuelta en noviembre de 2026:** sí; existe en `.github/PULL_REQUEST_TEMPLATE.md`, con los 16 criterios, para que el self-review de la sección 5 quede visible en el historial.
