# ADR-0015: Verificación automática de límites arquitectónicos (ArchUnit en CI)
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones

| Campo | Valor |
|---|---|
| **Estado** | ✅ Aprobado — 18-nov-2026 (ARCH-01 a ARCH-10), por el responsable del proyecto, tras la validación completa descrita al final de este documento. **Ampliación aprobada el 22-nov-2026** (ARCH-11 a ARCH-14), tal como está escrita |
| **Versión** | 1.2 — ampliado el 22-nov-2026 con ARCH-11 a ARCH-14; 1.1 — ajustado el 18-nov-2026 tras la validación completa |
| **Fecha** | Noviembre 2026 |
| **Documento relacionado** | las reglas del proyecto §7, `coding-standards.md` §2, `definition-of-done.md`, `system-design-document.md` §8 (riesgo de erosión hexagonal), ADR-0001 |

---

## Contexto

El riesgo "erosión de la arquitectura bajo presión de tiempo con un solo desarrollador" figura en el SDD §8, y su mitigación actual es **un checklist manual** del Definition of Done. La regla de dependencia (`domain/` sin Spring ni JPA; `application/` solo depende de `domain/`; ningún módulo usa tipos internos de otro) es no negociable según las reglas del proyecto §7.2, pero hoy nada la hace cumplir mecánicamente. Un checklist que alguien revisa a las 11 de la noche es la primera cosa que se omite.

## Decisión

1. Adoptar **ArchUnit** como biblioteca de pruebas de arquitectura, con las reglas en el módulo `app` (que ve todos los módulos) y **ejecutándose en cada build** (`mvn verify` y CI). Una violación **rompe el build**; no hay modo "advertencia".
2. Reglas iniciales obligatorias:

| ID | Regla |
|---|---|
| ARCH-01 | Ninguna clase en `..domain..` depende de `org.springframework..`, `jakarta.persistence..`, `jakarta.servlet..`, `com.fasterxml.jackson..` (Jackson 2), `tools.jackson..` (Jackson 3, el que usa Boot 4) ni `io.micrometer..` |
| ARCH-02 | `..application..` no depende de `..infrastructure..`, `jakarta.persistence..`, `jakarta.servlet..`, `org.springframework.web..`, `.data..`, `.jdbc..`, `.security..` ni `.dao..`. **Único Spring permitido:** `org.springframework.transaction..` (`@Transactional`, `TransactionOperations`), porque el caso de uso es la frontera transaccional (ADR-0016 exige cambio de dominio y fila del *outbox* en la misma transacción). Los casos de uso se registran como *beans* en `@Configuration` de `infrastructure`, sin estereotipos en `application`. Los puertos `*Port` son interfaces en `application` |
| ARCH-03 | `..infrastructure..` puede depender de `application` y `domain`; nunca al revés |
| ARCH-04 | **Superficie pública de un módulo** = sus interfaces de caso de uso con sus `Command`/`Result` (`..application.usecase..`) y sus eventos (`..domain.event..`). Ningún módulo usa otra cosa de otro módulo (ni `..infrastructure..`, ni `..domain.model..`, ni `..domain.service..`). Además, `..domain..` y `..application..` **no importan nada de otro módulo**: solo `..infrastructure.adapter..` lo hace, traduciendo a tipos propios (capa anticorrupción, el patrón de `CompatibilityScorePort` en las reglas del proyecto §7.1) |
| ARCH-05 | Sin ciclos entre módulos (`slices().matching("com.usac.jobseeker.(*)..").should().beFreeOfCycles()`), y ningún módulo de negocio ni `common` depende de `app` |
| ARCH-06 | `cv` y `job` no dependen de `matching`, `ats` ni `tracker` (dirección de dependencias de `domain-model.md` §5) |
| ARCH-07 | Entidades JPA (`@Entity`) solo en `..infrastructure.adapter.out.persistence..` |
| ARCH-08 | Controladores (`@Controller`/`@RestController`) solo en `..infrastructure.adapter.in.web..` |
| ARCH-09 | Todo `@Scheduled` reside en `..infrastructure..`, nunca en `application`/`domain` |
| ARCH-10 | Ninguna clase de `..application..` ni `..domain..` usa `TransactionSynchronization*` ni `TransactionSynchronizationManager`: ningún efecto con escritura en base de datos corre en `afterCommit` (ADR-0016; lección de TD-SEC-102 de Clavaris) |
| ARCH-11 | `common` solo depende del JDK (`java..`): es lo que todos los módulos comparten y no puede arrastrar un framework (ampliación del 22-nov-2026) |
| ARCH-12 | Los puertos (`*Port`) y los casos de uso (`*UseCase`) de `..application.usecase..` son interfaces: permiten sustituir una tecnología, o un *fake* en las pruebas, sin tocar a quien los usa (ampliación del 22-nov-2026) |
| ARCH-13 | Ningún campo se inyecta con `@Autowired` ni `@Inject`: todo por constructor, para poder construir una clase en una prueba sin Spring (ampliación del 22-nov-2026) |
| ARCH-14 | Un adaptador de entrada (`..adapter.in..`) no depende de uno de salida (`..adapter.out..`): hablan con los casos de uso, no los saltan (ampliación del 22-nov-2026) |

3. **Mientras un módulo no tenga clases**, las reglas se declaran con `allowEmptyShould(true)`: desde ArchUnit 1.3 una coincidencia vacía falla la prueba, y el esqueleto actual está vacío (patrón ya usado en `../Clavaris`).
4. Toda excepción a una regla requiere un comentario con ID `ARCH-xx`, motivo y fecha de revisión (mismo espíritu que el estándar de comentarios, las reglas del proyecto §8) y revisión explícita en el PR.
5. **Spring Modulith no se adopta ahora.** Aporta verificación de módulos y registro de eventos, pero acopla `application/` a anotaciones de framework (`@ApplicationModuleListener`) y duplica lo que ArchUnit ya cubre; la entrega fiable de eventos se resuelve con un *outbox* propio (ADR-0016). Se reevalúa si el costo de mantener ArchUnit supera el beneficio.

## Alternativas consideradas

| Alternativa | Por qué se rechazó |
|---|---|
| Solo checklist del DoD | Es el estado actual; depende de disciplina humana en el momento de mayor presión |
| Spring Modulith (`ApplicationModules.verify()`) | Complementario pero no necesario; añade dependencia con ciclo de versiones propio que debe seguir a Boot (ADR-0014) |
| Separar cada capa en módulos Maven distintos | Hace imposible el acceso a nivel de compilador, pero multiplica `pom.xml` (6 módulos × 3 capas) — sobrecarga excesiva para un solo desarrollador |
| Checkstyle/PMD con reglas de importación | Menos expresivo que ArchUnit para reglas de "dirección" entre paquetes y ciclos |

## Consecuencias

**Positivas**
- La regla de dependencia deja de ser una convención y pasa a ser un invariante del build.
- Documentación viva: las reglas son código legible.
- Refuerza NFR-MAINT (nuevo `NFR-MAINT-04`, ver revisión de arquitectura).

**Negativas / trade-offs aceptados**
- **Ampliación del 22-nov-2026 (ARCH-11 a ARCH-14).** Nacen de revisar qué haría barato cambiar de tecnología; cada una tiene su fixture que la viola y se prueba con las mismas pruebas negativas, y el ejemplo conforme las cumple todas. Ninguna exigió cambiar código existente.
- Las pruebas de arquitectura tardan unos segundos por build y requieren mantenimiento al renombrar paquetes.
- Un falso positivo puede bloquear temporalmente un PR; se resuelve con ajuste de la regla, no con desactivarla.

## Acciones derivadas

- Añadir `com.tngtech.archunit:archunit-junit5` (versión fijada en el `pom.xml` padre cuando se cree el primer test) y el paquete `app/src/test/java/.../architecture/`.
- ✅ Hecho el 18-nov-2026: ajustada la redacción de las reglas del proyecto §7.2 («`application/` depende solo de `domain/`») a la regla ARCH-02: no depende de `infrastructure` ni de web/persistencia, y solo admite transacciones de Spring.
- ✅ Hecho el 18-nov-2026: `definition-of-done.md` criterio #5 sustituido por «las pruebas ARCH-01 a ARCH-10 pasan» y resuelta su pregunta abierta sobre automatizar el chequeo.

## Preguntas abiertas

- ¿Se publican las reglas como parte de la documentación (`coding-standards.md`) para que sean fáciles de revisar sin leer código?

## Validación previa a la aprobación (18-nov-2026)

**Veredicto: aprobar con los ajustes ya aplicados.**

- **Contrastado con Clavaris,** que ya hace cumplir ArchUnit en `HexagonalArchitectureTest`: su regla para `application` es «no depende de `infrastructure`» (no «solo de `domain`»), y su capa `application` usa `@Transactional` (36 usos), `TransactionOperations` y `TransactionTemplate`. ARCH-02 tal como estaba escrito habría prohibido eso y contradecía a ADR-0016.
- **Ajustes:** ARCH-01 no cubría Jackson 3 (`tools.jackson`), con el que Boot 4 serializa; ARCH-04 no definía qué es «API pública»; ARCH-05 no impedía depender de `app`; nueva ARCH-10 contra escrituras en `afterCommit`; nota de coincidencia vacía.
- **Compatibilidad con el mapa de contextos** (`domain-model.md` §5): las flechas `cv→job`, `job→ats`, `ats→job`, `matching→cv/job` son todas «cliente depende de proveedor» (`ats` es cliente de `job` en ambos sentidos), sin ciclo; ARCH-06 es coherente.
- ArchUnit 1.5.1 verificado sobre Java 25 en `07-spike-bibliotecas-java-25.md`.

