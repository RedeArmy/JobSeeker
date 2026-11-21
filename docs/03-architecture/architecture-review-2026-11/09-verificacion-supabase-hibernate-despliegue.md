# Verificación de los puntos pendientes: Supabase, Hibernate, despliegue, Spring Session, Spring AI y recall

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 · 18-nov-2026 |
| **Relacionado** | ADR-0014, ADR-0016, ADR-0018, ADR-0020, ADR-0026, `data-model.md` §5.1, `anexos/embeddings-esquema/` |
| **Método** | Sondas desechables (Spring Boot 4.1.2 / Java 25 / Hibernate 7.4.13 / Spring AI 2.0.2) contra contenedores aislados: `pgvector/pgvector:pg16` (PostgreSQL 16.16, pgvector 0.8.7), Redis 7, Ollama 0.40.16 con `qwen3-embedding:0.6b` y PgBouncer 1.26.1. Todo se eliminó al terminar |

> Convención: ✅ ejecutado y observado · 🟡 verificado solo en parte o por emulación · 📄 solo documentación · ⚠️ no verificable aquí.

## 1. Resumen

| Punto | Estado | Resultado en una línea |
|---|---|---|
| Postgres de Supabase (versión de pgvector, `iterative_scan`) | 📄 / ⚠️ | No hay acceso a una instancia. La documentación confirma que `iterative_scan` exige pgvector ≥ 0.8.0 y que fijarlo en 0.7.x rompe la consulta; la versión real de un proyecto debe consultarse en él |
| *Pooler* de Supabase (modo transacción) | 🟡 | Emulado con PgBouncer: `SET` de sesión y bloqueos de sesión **se filtran**; `SET LOCAL` y `SKIP LOCKED` funcionan. Las sentencias preparadas no se pudieron reproducir |
| Hibernate: mapeo de dos slots | ✅ | Funciona, con tres hallazgos que cambian el código (§3) |
| Despliegue del cambio de modelo | ✅ | Cambiar columnas rompe a las instancias viejas: 24 operaciones correctas y 104 fallidas |
| Arranque con Redis caído (JobSeeker) | ✅ | Hacen falta las **dos** medidas; confirmado en la pila propia |
| Spring AI: `truncate` y `num_ctx` | ✅ | `num_ctx` global **no se envía**; por llamada sí |
| Vectores reales y recall de HNSW | 🟡 | 104 vectores reales de `qwen3-embedding:0.6b`; recall@20 ≈ 0,998 con el predicado del índice, pero **0,38 con un filtro selectivo si no se activa `iterative_scan`** (§8). El corpus de 10.000 es semisintético: cota optimista |
| Margen cuando Clavaris no responde | ✅ | Decidido: 2 h. Un tiempo agotado puede consumir el refresh token y matar la sesión al volver Clavaris (§9) |
| CSP estricta con HTMX en un navegador real | ⚠️ | Sin navegador disponible; sin verificar |

## 2. Supabase

**No se pudo verificar contra una instancia real** (no hay cuenta ni credenciales). Lo que sigue viene de la documentación oficial consultada el 18-nov-2026:

- **pgvector.** `hnsw.iterative_scan` existe desde pgvector 0.8.0 y la documentación de Supabase lo describe, pero un hilo de su repositorio reporta proyectos en 0.7.4 y una respuesta de mantenedores dice que la 0.8.0 exige subir la versión de Postgres del proyecto. Un informe de otro proyecto indica que en 0.7.x **fijar** `hnsw.iterative_scan` hace fallar la consulta. **Consecuencia:** la aplicación consulta `select extversion from pg_extension where extname='vector'` al arrancar y activa el parámetro solo si es ≥ 0.8.0 (ADR-0026).
- **Conexiones.** Puerto 6543 = *pooler* en modo transacción; puerto 5432 = modo sesión (desde el 28-feb-2025 el 6543 ya no ofrece modo sesión). El modo transacción **no** admite `SET` de sesión, `LISTEN/NOTIFY`, bloqueos de sesión ni, según la documentación vigente, sentencias preparadas.

Fuentes: <https://supabase.com/docs/guides/ai/vector-indexes/hnsw-indexes>, <https://github.com/orgs/supabase/discussions/34760>, <https://supabase.com/docs/guides/troubleshooting/supavisor-faq-YyP5tI>, <https://supabase.com/changelog/32755-supabase-connection-pooler-deprecating-session-mode-on-port-6543-on-february-28-2025>.

## 3. *Pooler* en modo transacción (emulación con PgBouncer 1.26.1)

Supavisor no es PgBouncer: esto es una **emulación** de las restricciones documentadas, no una prueba de Supabase.

| Prueba | Resultado |
|---|---|
| `SET hnsw.iterative_scan` (sin `LOCAL`) por un cliente, luego tres clientes nuevos | **Los tres vieron `strict_order`:** el valor se filtró a otros clientes |
| `SET LOCAL` dentro de una transacción | Vale dentro y desaparece al terminar; no contamina |
| `pg_advisory_lock(777)` por un cliente que se desconecta | **Sigue retenido en el servidor** y un segundo cliente consigue «tomarlo»: la exclusión mutua se rompe. Flyway usa un bloqueo de sesión: debe usar la conexión directa o el modo sesión |
| `SELECT … FOR UPDATE SKIP LOCKED` dentro de una transacción (sondeador del *outbox*) | Funciona (2 de 5 filas procesadas) |
| Sentencias preparadas del driver con Hibernate, con `max_prepared_statements=0` | **No concluyente:** 10 y 41 operaciones sin ningún error, porque con tan poca concurrencia el *pooler* devuelve la misma conexión de servidor. La restricción de Supavisor sigue siendo solo documental |
| Con `prepareThreshold=0` | Funciona; se adopta como precaución por la documentación de Supabase |

Una primera ejecución con la configuración por defecto de PgBouncer tampoco mostró errores porque PgBouncer ≥ 1.21 sí admite sentencias preparadas en modo transacción.

## 4. Hibernate y el esquema de embeddings

Sonda con dos mapeos de la misma tabla: **v1** («expandir»: dos slots, `@Array(length=1024)` y `@Array(length=768)`) y **v2** («contraer»: un slot de 768).

| Hallazgo | Detalle |
|---|---|
| `@EntityScan` cambió de paquete en Boot 4 | Ahora es `org.springframework.boot.persistence.autoconfigure.EntityScan` |
| `char(64)` no valida como `String` | Con `columnDefinition="char(64)"` la validación de esquema falla (`found [bpchar], but expecting [char(64) (Types#VARCHAR)]`) y el arranque se cae. Corrección: `@JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64)` |
| HQL `cosine_distance(c.embedding, :q)` | Hibernate genera el **operador** `(embedding<=>cast(? as vector))`, no la función, y con 10.000 filas usó el índice HNSW en 35 de 35 operaciones |
| `SET LOCAL hnsw.iterative_scan` desde JPA | Funciona con una consulta nativa dentro de la transacción |
| **La validación de esquema no comprueba la longitud del vector** | v2 (mapea 768) arrancó sin queja contra una columna de 1024 y falló en la primera escritura (`expected 1024 dimensions, not 768`). `ddl-auto=validate` **no** protege. Solución probada: comprobar `format_type(atttypid, atttypmod)` al arrancar y fallar con un mensaje claro si no coincide con la configuración |
| Plan genérico con `LIMIT` parametrizado | Si se **fuerza** (`plan_cache_mode=force_generic_plan`) el planificador abandona el índice. En modo normal Postgres compara costes y mantiene el plan personalizado; no fijar ese parámetro |
| Elección del planificador a 768 dim | Con 10.000 filas elige escaneo exacto + ordenación (coste 885 frente a 1065 del índice): **40 ms de mediana** frente a **6,7 ms** con el índice forzado. Ambos caben en NFR-PERF-01; a 1024 dim eligió el índice, así que la elección es frágil |

## 5. Despliegue del cambio de modelo

Escenario: una instancia v1 trabajando (escribe en los dos slots y consulta similitud cada 250 ms) y se ejecuta el cambio de columnas del procedimiento (`anexos/embeddings-esquema/02-cambio-de-modelo.sql`, paso 5).

| Situación | Resultado |
|---|---|
| Cambio ejecutado **mientras v1 trabaja** (1,6 s) | **24 operaciones correctas y 104 fallidas**, todas con `column embedding_next does not exist`, hasta reemplazar la instancia |
| Instancia v1 que **arranca después** del cambio | No arranca: `missing column [embedding_next]` |
| v2 después del cambio | 34 de 34 correctas |
| v2 **antes** del cambio (columna de 1024) | Arranca (la validación no mira la longitud) y falla al escribir |
| Comprobación de longitud al arrancar | Alineada: arranca. Desalineada: falla al arrancar con «La columna embedding es vector(768) pero la configuración espera vector(1024)» |

**Conclusión de diseño.** El cambio por renombrado exige que migración y versión de la aplicación salgan juntas. Con **una instancia** (la topología de ADR-0026: una VM con Compose y Flyway al arrancar la versión nueva) la ventana es el reinicio, de segundos. **Con varias instancias no es seguro**: todas las instancias viejas fallan entre el cambio y su reemplazo. Si se escala horizontalmente, hay que pasar a un diseño sin renombrado (dos slots permanentes y un indicador de slot activo en configuración), que **no se ha probado** y queda como trabajo futuro.

## 6. Arranque con Redis caído en la pila de JobSeeker

Sonda con `@EnableRedisIndexedHttpSession` sobre Boot 4.1.2 y un puerto sin Redis:

| Modo | Resultado |
|---|---|
| Ingenuo | No arranca: `enableRedisKeyspaceNotificationsInitializer` |
| Solo `ConfigureRedisAction.NO_OP` | No arranca: el contenedor de escucha de sesiones (`springSessionRedisMessageListenerContainer`) |
| `NO_OP` + arranque tolerante con reintento | Arranca; *liveness* 200; `/ping` 200; una petición con sesión da 500 |

*Readiness* respondió 200 con Redis caído (Boot no incluye Redis en ese grupo); con `management.endpoint.health.group.readiness.include=readinessState,redis` da 503. Al levantar Redis sin reiniciar la aplicación, la escucha se reanudó a los ~62 s, `readiness` volvió a 200 y las sesiones funcionaron.

## 7. Spring AI y Ollama

Se capturó la petición HTTP real con un *proxy*:

| Configuración | Lo que llega a Ollama |
|---|---|
| `spring.ai.ollama.embedding.options.truncate=false` | `"truncate": false` ✅ |
| `spring.ai.ollama.embedding.options.keep-alive=10m` | `"keep_alive": "10m"` ✅ |
| `spring.ai.ollama.embedding.options.num-ctx=64` | **`"options": {}`: no se envía** ❌, sin aviso |
| `numCtx(64)` por llamada | `"options": {"num_ctx": 64}` ✅ |
| Por llamada con solo `numCtx` | Conserva `truncate=false` y Ollama responde HTTP 400 |

Con ambas opciones enviadas, Ollama rechaza un texto de ~150 tokens con `num_ctx=64` en 7 ms (HTTP 400, «the input length exceeds the context length»); con `truncate=true` lo trunca y devuelve el vector en ~20 s. Spring AI lo lanza como `NonTransientAiException`. Tiempos de esta VM (sin AVX, 2 CPU): 13 s para un texto corto en frío, entre 6 y 15 s por texto de ~85 tokens.

## 8. Recall de HNSW con vectores reales

Se embebieron con Ollama los **104 textos reales del bake-off** (80 candidatos y 24 vacantes) con `qwen3-embedding:0.6b` (1024 dim, `truncate=false`, `num_ctx=1024`): 37 min en esta VM. Con ellos se construyeron tres corpus de 10.000 candidatos y se comparó, para las 24 vacantes reales, el top-20 del índice HNSW con el top-20 **exacto** (escaneo secuencial forzado). Scripts, vectores y resultados: `anexos/embeddings-esquema/recall/`.

**Geometría real:** coseno vacante-candidato entre 0,17 y 0,85 (media 0,41) y coseno medio entre candidatos reales de **0,51** (los embeddings reales son anisótropos; los vectores uniformes no).

| Corpus | Filtro | `iterative_scan` | recall@20 con `ef_search`=40 | con 100 |
|---|---|---|---|---|
| Real aumentado | solo descubribles (predicado del índice) | indiferente | 0,998 | 1,000 |
| Real aumentado | + país (~14 %) | **off** | **0,38** | 0,90–0,93 |
| Real aumentado | + país | `relaxed_order` | 0,996–1,000 | 0,998–1,000 |
| Uniforme aleatorio | descubribles | indiferente | 0,28 | 0,54 |

**Conclusiones:**
1. HNSW con la configuración por defecto es suficiente para la consulta que el índice cubre.
2. **`hnsw.iterative_scan` es obligatorio para cualquier consulta con un filtro adicional**: sin él el recall cae a 0,38. Por tanto la versión de pgvector del proyecto de Supabase (≥ 0,8.0) es un **requisito funcional**, no una optimización (§2). Si no estuviera disponible, la alternativa medida es subir `ef_search` a 100 (recall 0,90–0,93), insuficiente para un producto de coincidencia.
3. Los vectores uniformes aleatorios dan un recall pésimo (0,28) y **no representan texto real**. La prueba de latencia del esquema usó vectores aleatorios: mide planes y tiempos, **no** calidad de búsqueda.

**Advertencia metodológica.** Los 104 vectores son reales; los corpus de 10.000 **no**: son combinaciones convexas de 2-3 vectores reales más ruido, renormalizadas. Heredan la geometría pero parten de solo 80 puntos, así que son más fáciles de indexar que un corpus real de 10⁴ perfiles distintos. Léanse como cota **optimista**. Según la documentación de pgvector (no verificado aquí), con `relaxed_order` los resultados pueden salir ligeramente desordenados por distancia: si el orden exacto importa, hay que reordenar la salida.

## 9. Margen cuando Clavaris no responde

Se midió el comportamiento de la renovación con el cliente real de Spring Security 7 contra Clavaris (`master` `c10281b3`, aislado) en cinco situaciones:

| Situación | Resultado |
|---|---|
| Clavaris **colgado** (SIGSTOP), cliente **sin** tiempos de espera propios | La petición **no termina** (agoté el límite de 45 s); la segunda petición de la misma sesión queda **bloqueada detrás del cerrojo** (agotamiento de hilos) |
| Clavaris colgado, con tiempos de espera (2 s conexión, 5 s lectura) | Falla a los 5 s (`invalid_token_response`, causa `HttpTimeoutException`). La segunda petición **repite otros 5 s** (10 s en total para dos peticiones) |
| Clavaris **caído** (proceso terminado) | Falla en 12–16 ms (`invalid_token_response`, causa de red). El token **no se consume** |
| **502** inmediato de un proxy delante de Clavaris | Falla en 13–26 ms (`invalid_token_response`, causa `BadGateway`). El token no se consume |
| Postgres de Clavaris detenido | **No** produce un 500: Clavaris espera una conexión y la petición agota el tiempo (como en el caso colgado) |

**Recuperación.** Con conexión rechazada o 502 la **misma sesión** siguió funcionando al volver Clavaris (renovación en 1,2 s; arranque de Clavaris 40–64 s). Con un tiempo agotado, en cambio, la sesión **murió con `invalid_grant`** al volver Clavaris.

**Pérdida de respuesta (reproducida de forma determinista).** Un proxy reenvía la renovación a Clavaris (que responde 200 y rota el token) y retrasa 9 s la respuesta; el cliente se rinde a los 5 s. El siguiente intento presenta el token ya consumido: `invalid_grant`, Clavaris registra eventos `refresh_token.reuse_detected` y Spring borra el cliente autorizado (`client_authorization_required`). Clavaris no tiene ventana de tolerancia a la reutilización.

**Premisa de la exposición.** Con Clavaris detenido, el endpoint de administración no responde (HTTP 000): **nadie puede suspender ni borrar cuentas durante una caída**. Por tanto, mantener sesiones abiertas durante la caída solo expone a las suspensiones hechas en los ≤ 10 min previos y aún no detectadas.

**Datos de contexto.** Clavaris compromete ≥ 99,5 % de disponibilidad en sus endpoints OIDC (hasta ~3,6 h al mes, `nfr-quality-attributes.md` de Clavaris); su restauración medida es de ~14 s más el arranque. Latencia normal de la renovación observada: 83–1.246 ms.

**Decisión del responsable (19-nov-2026): margen por defecto de 2 horas**, configurable y siempre acotado por el límite absoluto de sesión de 8 h (opciones descartadas: 30 min, que cierra la web a todos ante un incidente mayor, y 8 h, que equivale a no cerrar nunca por una caída). Reglas derivadas en ADR-0020 (reglas 2 y 12).

**Carencias de Clavaris registradas el 19-nov-2026:** `TD-SEC-103` (P2, ventana de tolerancia a la reutilización) y `TD-FUT-081` (P3, lectura idempotente del estado de cuenta). **Ambas están cerradas en Clavaris y verificadas el 20-nov-2026** (addendum 12 de `06-spike-contrato-clavaris.md`).

## 10. No verificado

- Una instancia real de Supabase (versión de pgvector, `iterative_scan`, Supavisor).
- El recall con un corpus **real** de miles de perfiles distintos, con filtros más selectivos y el reordenamiento de `relaxed_order`.
- La restricción de sentencias preparadas del *pooler*.
- CSP estricta y cookie `__Host-` con HTMX en un navegador.
- El diseño de dos slots permanentes con indicador de slot activo (alternativa multi-instancia).
- Despliegue con más de una instancia real.
