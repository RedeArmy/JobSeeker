# 08 — Medición de Ollama y *bake-off* piloto de modelos de embeddings

| Campo | Valor |
|---|---|
| **Estado** | 🟡 En revisión |
| **Versión** | 1.0 · 15-nov-2026 |
| **Relacionado** | ADR-0004 (Ollama local), **ADR-0018 (propuesto)**, ADR-0003, `02-hld-system-design.md` §4 y §8, `07-spike-bibliotecas-java-25.md`, anexo `anexos/bakeoff-embeddings/` |
| **Método** | Ollama 0.40.16 en un contenedor aislado (imagen `ollama/ollama`), cinco modelos de embeddings, un conjunto **sintético** de 24 vacantes y 56 candidatos (1.344 pares) con relevancia 0–3 derivada de familia profesional y solapamiento de habilidades, más sondeos de sesgo por nombre y de truncamiento de contexto. Intervalos de confianza por *bootstrap* pareado por vacante (10.000 remuestreos) |

> Convención: ✅ medido por mí · ⚠️ no verificado / con reservas. Todo el código y los resultados están en `anexos/bakeoff-embeddings/` y se pueden repetir.

---

## 1. Resumen

1. **El modelo que fijan hoy las reglas del proyecto §3/§5 y el ADR-0004 (`nomic-embed-text` v1.5) rinde significativamente peor que los alternativos y que una línea base léxica simple** en este piloto: NDCG@10 = 0,780 frente a 0,848 (TF-IDF) y 0,887–0,906 (los otros cuatro). Los prefijos de tarea no cambian nada (0,779 sin prefijo).
2. **Los cuatro modelos multilingües no se pueden distinguir entre sí** con 24 vacantes (diferencias ≤ 0,02, intervalos solapados). La elección entre ellos debe basarse en criterios secundarios (§5) y en el protocolo completo de 300 pares, no en este piloto.
3. **El coseno bruto no es un porcentaje y no es comparable entre modelos**: el mismo coseno 0,70 equivale a 5,6 % de probabilidad de relevancia con `nomic` v1.5 y a 99,9 % con `embeddinggemma` (§4.3). La calibración por modelo es obligatoria.
4. **Los embeddings agrupan bien por familia profesional pero gradúan mal el encaje fino** (diferencia de coseno entre relevancia 3 y 2 de solo 0,007–0,038): respalda el diseño híbrido del ADR-0003, donde la capa estructurada debe graduar las habilidades.
5. **Incluir el nombre en el texto a embeber perturba el coseno más que la diferencia entre un buen y un mejor encaje** (−0,04 a −0,10 frente a 0,02–0,04) y, con `qwen3`, los apellidos mayas pierden 0,031 adicionales. Confirma embeber una representación canónica **sin nombre**.
6. **Truncamiento silencioso confirmado**: con la opción por defecto, un texto de ~900 tokens se recorta a 512 en `nomic-v2-moe` sin avisar. Hay que enviar siempre `truncate=false`.
7. **Esta VM no sirve para medir tiempos** (§2). La calidad sí.

---

## 2. Rendimiento (con reservas importantes)

**Entorno:** VM VirtualBox, 3 vCPU de un i7-8850H (2,6 GHz), 9,2 GB de RAM. **La VM no expone AVX/AVX2/F16C** (solo SSE4.2), de modo que Ollama cargó el *backend* `libggml-cpu-sse42.so`. Un servidor en la nube sí expone AVX2: **todo tiempo de esta sección es una cota pesimista**.

### 2.1 Coste por modelo (CPU libre, un solo modelo residente) ✅

| Modelo | Parámetros / cuantización | ms por token (caliente) | Carga en frío | Dim. | Contexto del modelo |
|---|---|---|---|---|---|
| `nomic-embed-text` v1.5 | 137 M / F16 | 38 | 15,7 s | 768 | 2.048 (Ollama configura 8.192) |
| `nomic-embed-text-v2-moe` | 475 M / F16 | 38 | 2,8 s | 768 | **512** |
| `qwen3-embedding:0.6b` | 596 M / Q8_0 | 42 | 28,9 s | 1024 | 32.768 (el *runner* usó `n_ctx` = 4.096) |
| `embeddinggemma` | 308 M / BF16 | 64 | 1,7 s | 768 | 2.048 |
| `bge-m3` | 567 M / F16 | 97 | 42,6 s | 1024 | 8.192 |

El coste es **lineal con la longitud** (≈ 37 ms/token de 100 a 830 tokens con `nomic`).

### 2.2 Por qué los tiempos no son fiables ⚠️

- La **misma petición** (un lote de 8 textos, 631 tokens con `qwen3`) tardó entre **66 s y 247 s** según el momento (105–391 ms/token), con el huésped al 92–96 % inactivo y *steal* en 0 %. VirtualBox no reporta *steal*: cuando el anfitrión quita CPU a la VM, el huésped lo ve como inactividad.
- **La VM se congeló tres veces y fue reiniciada manualmente** por el usuario (pantalla negra, sin poder iniciar sesión): 14-nov 23:23, 15-nov 00:40 y 15-nov 07:39. Los dos primeros ocurrieron tras 25–60 min de carga sostenida. `sysstat` muestra, antes de cada uno, una **cola de ejecución de 16–31 tareas y carga media de 30–61 con la CPU 77–91 % inactiva**, la firma de vCPU que no ejecutan código del huésped (el anfitrión no los planifica). En el arranque anterior hubo además *soft lockups* de 22–23 s y 102–204 avisos de `rtkit` por arranque (0 en el actual, sin carga). El tercero ocurrió sin carga mía y no se pudo atribuir. Detalle y mitigaciones: ver el seguimiento del estado de los puntos no resueltos.
- Limitar el contenedor a 2 CPU empeoró los tiempos de forma errática y no resolvió nada; no uso esas cifras.

### 2.3 Consecuencias para el diseño

- Con un CV canónico de ~400 tokens, el peor caso medido aquí (38–97 ms/token) supone 15–40 s por embedding. Como el diseño es **asíncrono** (ADR-0016, SLO de frescura p95 ≤ 90 s con *debounce* de 30–60 s), incluso eso cabe, pero con poco margen: **hay que repetir esta medición en el hardware de producción** (ADR-0026) antes de fijar el SLO.
- **Contexto efectivo = el menor entre el del modelo y `num_ctx` del *runner***: `qwen3` soporta 32.768 pero Ollama lo ejecutó con 4.096. Fijar `num_ctx` explícito en la configuración.
- ✅ **Truncamiento silencioso:** `nomic-v2-moe` con un texto de ~900 tokens y `truncate=true` (por defecto) devolvió `prompt_eval_count = 512` sin error (perdió casi la mitad del texto); con `truncate=false` responde `HTTP 400 "the input length exceeds the context length"`. El adaptador debe enviar **siempre `truncate=false`**, medir tokens antes de enviar y, si excede, aplicar una estrategia explícita (resumen determinista de secciones o troceado con promedio) que quede registrada (`embedding_truncated`, `03-lld.md` §4.2).

---

## 3. Calidad de ranking (piloto sintético)

**Qué mide:** para cada vacante, ordenar los 56 candidatos por coseno y comparar con la relevancia verdadera (0–3). NDCG@10 y P@5 (relevante = nivel ≥ 2). Dirección vacante → candidatos. Cada modelo con su formato recomendado (prefijos `search_query:`/`search_document:` en `nomic`; instrucción de tarea en `qwen3`; `task: search result | query:` en `embeddinggemma`).

| Variante | Dim. | NDCG@10 [IC 95 %] | P@5 | Vacante ES | Vacante EN |
|---|---|---|---|---|---|
| **qwen3-embedding:0.6b** | 1024 | **0,906** [0,873; 0,936] | 0,942 | 0,902 | 0,915 |
| nomic-embed-text-v2-moe (asimétrico) | 768 | 0,900 [0,867; 0,930] | 0,908 | 0,900 | 0,901 |
| embeddinggemma | 768 | 0,900 [0,868; 0,930] | 0,917 | 0,889 | 0,922 |
| bge-m3 | 1024 | 0,887 [0,856; 0,918] | 0,917 | 0,888 | 0,885 |
| *TF-IDF léxico (línea base)* | — | *0,848* [0,797; 0,895] | 0,850 | 0,847 | 0,849 |
| nomic-embed-text v1.5 (asimétrico) | 768 | 0,780 [0,732; 0,828] | 0,833 | 0,748 | 0,844 |
| nomic-embed-text v1.5 (sin prefijo) | 768 | 0,779 [0,728; 0,827] | 0,817 | 0,754 | 0,829 |

Diferencias pareadas (IC 95 % *bootstrap* sobre las 24 vacantes):

| Comparación | Diferencia | Veredicto |
|---|---|---|
| Cualquiera de los 4 nuevos − `nomic` v1.5 | +0,107 a +0,126 | **Significativa** |
| `qwen3` / `v2-moe` / `gemma` − TF-IDF | +0,052 a +0,059 | **Significativa** |
| `bge-m3` − TF-IDF | +0,040 [0,000; +0,082] | Marginal |
| `nomic` v1.5 − TF-IDF | **−0,067** [−0,112; −0,024] | **Significativamente peor** |
| `qwen3` − `bge-m3` / `v2-moe` / `gemma` | +0,019 / +0,006 / +0,006 | **No distinguible** |

La variante simétrica de `nomic` v1.5 (`clustering:`) **no se completó**: la detuve por prioridad, dado que los prefijos ya habían mostrado efecto nulo.

---

## 4. Hallazgos de comportamiento

### 4.1 Idioma
Coseno medio entre pares **relevantes** del mismo idioma → de idioma cruzado: TF-IDF 0,737 → **0,230**; `bge-m3` 0,743 → 0,713 (retiene el 96 %); `v2-moe` 0,734 → 0,657 (89 %); `qwen3` 0,748 → 0,664 (89 %); `gemma` 0,603 → 0,544 (90 %); `nomic` v1.5 0,860 → 0,722 (84 %). Los modelos multilingües resuelven el caso vacante en español/CV en inglés (y viceversa) que el léxico no puede. `nomic` v1.5 rinde peor con vacantes en español (0,748) que en inglés (0,844).

### 4.2 Graduación fina (relevancia 3 vs 2, misma familia)
Diferencia de coseno medio: `qwen3` +0,038, `v2-moe` +0,024, `bge-m3` +0,015, `gemma` +0,012, `nomic` v1.5 +0,007, TF-IDF +0,050. Los modelos recuperan **la familia correcta, no el grado de coincidencia de habilidades**.

### 4.3 Calibración
| Variante | Coseno medio pares irrelevantes → relevantes (nivel 3) | Coseno 0,70 equivale a P(relevante) |
|---|---|---|
| `nomic` v1.5 | 0,650 → 0,801 (rango útil 0,15) | **5,6 %** |
| `bge-m3` | 0,509 → 0,736 | 66,4 % |
| `v2-moe` | 0,392 → 0,710 | 88,8 % |
| `qwen3` | 0,328 → 0,728 (**rango útil 0,40**) | 90,1 % |
| `embeddinggemma` | 0,276 → 0,582 | 99,9 % |

Ajuste de Platt sobre el propio piloto (153 positivos; ilustrativo, las probabilidades extremas están sobreajustadas). Los pares **sin relación alguna** puntúan 0,65 con `nomic` v1.5: mostrar ese número como "65 %" sería engañoso.

### 4.4 Sesgo por nombre (sondeo de viabilidad, **no** auditoría) ⚠️
20 candidatos en español con "Nombre: X." antepuesto; 2 nombres por grupo (ladino, maya, anglosajón); coseno hacia vacantes con relevancia ≥ 2 (52 pares).

| Modelo | Δ al añadir cualquier nombre | Ladino − maya | Anglo − maya | Anglo − ladino | Referencia: escalón rel3−rel2 |
|---|---|---|---|---|---|
| `qwen3` | −0,047 a −0,100 | **+0,032** [+0,028; +0,036] | **+0,032** [+0,027; +0,037] | +0,000 (n.s.) | 0,038 |
| `v2-moe` | −0,039 a −0,062 | +0,007 [+0,005; +0,010] | −0,004 [−0,007; −0,001] | −0,012 [−0,014; −0,009] | 0,024 |

- Con `qwen3`, los apellidos mayas pierden **84 % de un escalón de relevancia** respecto a los otros dos grupos, que son iguales entre sí: no se explica por desajuste de idioma. Con `v2-moe` el patrón es distinto y de menor magnitud (el grupo anglosajón es el más bajo).
- **Limitación decisiva:** en cada grupo usé el mismo nombre para los 20 candidatos, y un desplazamiento constante **no altera el orden** (NDCG@10 sin cambios apreciables: 0,945 → 0,949–0,959). El riesgo real es el escenario **mixto** (candidatos con apellidos distintos compitiendo por la misma vacante), que no medí. Además: 2 nombres por grupo con longitud y tokenización diferentes, 20 candidatos, y solo nombres (no instituciones, ciudades, foto).

---

## 5. Implicaciones para el diseño y recomendaciones

| # | Recomendación | Base |
|---|---|---|
| 1 | **Dejar de usar `nomic-embed-text` v1.5 como modelo por defecto** (enmienda al ADR-0004; ver **ADR-0018**) | §3: peor que los cuatro alternativos (+0,11 a +0,13) y que TF-IDF |
| 2 | **Preseleccionar** `qwen3-embedding:0.6b`, `bge-m3` y `nomic-embed-text-v2-moe` para el protocolo completo de 300 pares (`01-competencia-y-requerimientos.md` §6) | Indistinguibles en el piloto; criterios secundarios abajo |
| 3 | **Calibrar por modelo** (Platt/isotónica) y recalibrar al cambiar de modelo o de `algorithm_version` | §4.3 |
| 4 | **Mantener el diseño híbrido (ADR-0003)**: capa semántica para familia/idioma, capa estructurada para graduar habilidades | §4.2 |
| 5 | **Embeber solo la representación canónica sin nombre** y añadir la prueba contrafactual al CI con **nombres mezclados dentro del mismo grupo de candidatos** | §4.4 |
| 6 | **`truncate=false` siempre**, `num_ctx` explícito, medición previa de tokens, estrategia de troceado registrada | §2.3 |
| 7 | **Dirección del cálculo**: `qwen3` y `gemma` usan formatos distintos para consulta y documento; el score es bidireccional (candidato↔vacante) y aquí solo medí vacante→candidatos. Hay que decidir si se guardan dos vectores por entidad o se usa un formato simétrico, y medirlo | No medido |

Criterios secundarios entre los tres preseleccionados:

| | `qwen3-embedding:0.6b` | `bge-m3` | `nomic-embed-text-v2-moe` |
|---|---|---|---|
| NDCG@10 piloto | 0,906 | 0,887 | 0,900 |
| Mejor graduación fina / rango de coseno | **Sí** (+0,038; 0,40) | No | Intermedio |
| Retención entre idiomas | 89 % | **96 %** | 89 % |
| Contexto utilizable | 4.096 por defecto (hasta 32.768) | 8.192 | **512** (obliga a trocear) |
| Coste (ms/token en esta VM) | 42 | 97 | 38 |
| Cuantización | Q8_0 | F16 | F16 |
| **Licencia declarada** (ficha oficial en Hugging Face, leída el 16-nov-2026) | **Apache-2.0** | **MIT** | **Apache-2.0** |

**Nota de licencias (verificada el 16-nov-2026).** Se leyó el encabezado `license:` de la ficha oficial de cada modelo: Qwen3-Embedding-0.6B → `apache-2.0` (base `Qwen3-0.6B-Base`); bge-m3 → `mit`; nomic-embed-text-v2-moe → `apache-2.0`, con pesos, código y datos de entrenamiento declarados abiertos. Las tres permiten uso comercial, modificación y autoalojamiento; si se **redistribuye** el modelo (p. ej. dentro de una imagen Docker publicada) hay que incluir el texto de la licencia y los avisos de derechos. Para uso interno de inferencia en servidor propio, las obligaciones prácticas son mínimas. **Reservas:** (1) se leyó la metadata de la ficha, no los archivos `LICENSE`; (2) la licencia del modelo no cubre las de cada conjunto de datos de entrenamiento (la ficha de nomic remite al repositorio de código para ello); (3) la ficha de Qwen llama "proprietary" a la serie en una frase descriptiva pese a declarar Apache-2.0, por lo que el texto de `LICENSE` merece una lectura directa; (4) las páginas de Ollama **no** declaran licencia, así que la del modelo empaquetado se toma de la ficha original; (5) **`embeddinggemma`**: su ficha oficial es de acceso restringido (HTTP 401) y no se pudo leer; fuentes secundarias indican que la v1 se rige por los *Gemma Terms of Use* (con política de uso prohibido que debe trasladarse a terceros) y que una v2 saldría bajo Apache-2.0, pero **no está verificado** y no es candidato preseleccionado.

---

## 6. Limitaciones del piloto (leer antes de citarlo)

- **Datos sintéticos y plantillados**: listas de habilidades con la verdad-terreno calculada por solapamiento de esas mismas habilidades, lo que **favorece al método léxico**; los textos miden ~80–105 tokens frente a 300–600 de un CV real. No hay CV reales.
- **24 vacantes y 56 candidatos**: los intervalos son anchos; el orden entre los cuatro primeros no es concluyente.
- **No sustituye** el protocolo de evaluación (300 pares, doble etiquetado, κ ≥ 0,6); **P@5 de este piloto no es la métrica de la tesis** y no demuestra ni refuta la meta de 0,85.
- Una sola dirección (vacante → candidatos), una sola semilla, español/inglés únicamente.
- Tiempos: VM sin AVX y con anfitrión que estrangula (§2.2).
- **Errores míos durante la medición**, por transparencia: un `pkill` que cortó mi propio *shell*, un diagnóstico de "atascado" erróneo (el sondeo estaba terminando) y una hipótesis sobre el tope de CPU que no se sostuvo. No afectan a los resultados de calidad, que son deterministas.

## 7. Qué falta

1. Repetir **§2** en el hardware real de producción (AVX2) y con CV canónicos reales.
2. Ejecutar el protocolo de 300 pares y la prueba contrafactual con **nombres mezclados** e instituciones/ciudades.
3. Medir la dirección candidato → vacante y la opción de vector único simétrico.
4. ~~Verificar las licencias~~ **Hecho el 16-nov-2026 para los tres preseleccionados** (ver la nota de licencias en §5). Pendiente solo lo señalado allí: texto de los archivos `LICENSE`, licencias de los datos de entrenamiento y `embeddinggemma`.
5. Probar la integración con Spring AI contra Ollama real (`truncate`, `num_ctx`).
