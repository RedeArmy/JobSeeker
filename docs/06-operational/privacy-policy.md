# Política de Privacidad
## Sistema Inteligente de Análisis de Compatibilidad CV-Vacante con CRM de Postulaciones ("JobSeeker")

| Campo | Valor |
|---|---|
| **Estado** | 🔴 Borrador técnico — **no publicar sin revisión legal profesional** |
| **Versión** | 1.0 |
| **Fecha** | Agosto 2026 |
| **Documentos relacionados** | Business Rules (sección 8, BR-DATA-01 a 06), Security Architecture, ADR-0013, Terms of Service |

---

> ⚠️ **Este documento es un borrador técnico, no un documento legal listo para publicarse.** Se redactó a partir de las reglas de negocio y el mecanismo de datos ya documentados en este repositorio, para que el contenido sea consistente con lo que el sistema realmente hace — no a partir de una plantilla genérica. **No sustituye la revisión de un abogado** con conocimiento de la legislación aplicable (Guatemala y cualquier otra jurisdicción donde el producto opere), quien debe validar como mínimo: base legal para el tratamiento de datos, requisitos de consentimiento, transferencias internacionales de datos (Supabase, Ollama si corre en la nube, proveedores de OAuth social en Clavaris), y cualquier obligación sectorial aplicable a intermediación laboral. Ningún dato real de un usuario debe procesarse bajo esta política hasta que se apruebe.

## 1. Quiénes somos

[Completar: razón social o nombre del responsable del tratamiento, dirección, medio de contacto — pendiente de definir junto con `project-charter.md` §10, que todavía no tiene esta información resuelta].

JobSeeker es una plataforma que calcula compatibilidad entre perfiles profesionales (candidatos) y vacantes laborales (publicadas por reclutadores), con seguimiento de postulaciones. La autenticación e identidad de tu cuenta la gestiona un proveedor separado ("Clavaris") que actúa como encargado del tratamiento para este fin específico.

## 2. Qué datos recolectamos

| Categoría | Datos concretos | De dónde vienen |
|---|---|---|
| Identidad y acceso | Email, contraseña (nunca almacenada en texto plano — hash Argon2id), identidades sociales vinculadas (Google/GitHub si las usas) | Los proporcionas al registrarte, gestionados por Clavaris |
| Perfil profesional | Nombre, titular profesional, biografía, foto, experiencia laboral, formación académica, certificaciones, proyectos | Los cargas directamente o los extraemos del CV que subes |
| Datos de postulación (candidatos) | CV cargado, habilidades, país de residencia, autorización de trabajo, modalidad e idioma preferidos, disposición a reubicarte, expectativa salarial, respuestas a preguntas de postulación | Los declaras en tu perfil o al postular a una vacante específica |
| Datos de reclutador | Empresa/agencia que representas, áreas de especialización, vacantes publicadas | Los proporcionas al usar la capacidad de reclutador |
| Datos técnicos | Dirección IP, identificador de sesión, registros de acceso (sin contenido de tu CV ni contraseña — BR-DATA-01) | Generados automáticamente al usar la plataforma |

## 3. Para qué usamos tus datos

- Calcular tu score de compatibilidad con vacantes (o de un candidato contigo, si eres reclutador) y mostrarte su desglose explicado.
- Permitirte postular, dar seguimiento a tus postulaciones, y a los reclutadores gestionar su proceso de selección.
- Sugerirte proactivamente a reclutadores compatibles, si tu perfil está marcado como descubrible (lo controlas tú, y puedes desactivarlo en cualquier momento).
- Generar tu CV en PDF, o el CV de marca que un reclutador arma a partir de tus datos para reenviarte a un cliente.
- Seguridad de la cuenta: detectar accesos indebidos, aplicar límites de uso razonables (rate limiting), prevenir fraude.
- Nunca usamos tus datos para entrenar modelos de terceros ni los vendemos.

## 4. Con quién compartimos tus datos

- **Reclutadores**, solo cuando postulas a su vacante, o si tu perfil es descubrible y eres sugerido como candidato compatible — nunca con reclutadores de vacantes en las que no participas.
- **Clavaris** (proveedor de identidad), como encargado del tratamiento exclusivamente para gestionar tu autenticación.
- **Proveedores de infraestructura**: Supabase (base de datos en producción), y el proveedor de cómputo donde corra Ollama, si aplica — ambos como encargados del tratamiento, nunca como terceros con acceso independiente a tus datos.
- No vendemos ni cedemos tus datos a terceros con fines publicitarios.

## 5. Cuánto tiempo conservamos tus datos

Mientras tu cuenta esté activa, con una excepción: el registro de qué perfiles de candidato mostró la plataforma a cada reclutador (solo identificadores, sin tus datos de contacto) se conserva **90 días** y después se borra. Sirve para limitar cuántos perfiles puede ver un reclutador y para investigar un uso abusivo.

Si solicitas la eliminación de tu cuenta:

1. Entra en un periodo de gracia de 30 días (configurable), durante el cual puedes cancelar la solicitud volviendo a iniciar sesión.
2. Vencido ese plazo, anonimizamos tu información personal identificable (nombre, contacto, CV, foto, y en tu perfil de candidato también tus habilidades, idiomas, autorizaciones de trabajo, expectativa salarial y disponibilidad) — no la borramos de forma que rompa el historial de procesos de selección en los que ya participaste, porque el reclutador que evaluó tu postulación tiene derecho a conservar su propio historial de decisión. Tu identidad de acceso (email, contraseña, cuentas sociales vinculadas) se elimina por completo del proveedor de identidad.

Ver sección 8 (tus derechos) para solicitar esto.

## 6. Tus derechos

- **Acceso**: puedes ver toda la información de tu perfil directamente en la plataforma.
- **Rectificación**: puedes editar tu perfil en cualquier momento.
- **Eliminación**: puedes solicitar la eliminación de tu cuenta (sección 5).
- **Oposición al descubrimiento**: puedes desactivar la visibilidad de tu perfil ante sugerencias de reclutadores en cualquier momento, sin afectar tu capacidad de postular ni la visibilidad de tus postulaciones ya hechas.
- [Completar con el mecanismo de contacto real para ejercer estos derechos, y con cualquier derecho adicional que exija la legislación aplicable — portabilidad de datos, por ejemplo, no está resuelta todavía a nivel de producto].

## 7. Seguridad

Cifrado en tránsito (TLS), contraseñas con Argon2id, autenticación con tokens de vida corta y rotación, límites de tasa contra abuso, y separación estricta de qué reclutador puede ver los datos de qué candidato. Detalle técnico completo en `security-architecture.md` (documento interno, no público).

## 8. Cambios a esta política

[Completar: mecanismo de notificación de cambios — pendiente de definir].

## 9. Contacto

[Completar: canal de contacto para preguntas de privacidad o ejercicio de derechos].
