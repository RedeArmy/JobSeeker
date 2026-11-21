# Anexo — Arnés del *bake-off* piloto de embeddings

Reproduce `../../08-medicion-ollama-y-bakeoff-embeddings.md`. **Piloto sintético**: sirve para comparar modelos y detectar problemas de integración, no para demostrar la métrica de precisión de la tesis.

## Contenido

| Archivo | Qué hace |
|---|---|
| `data.py` | Genera `dataset.json` (semilla fija): 8 familias profesionales (técnicas y no técnicas), 80 candidatos (ES/EN), 24 vacantes, relevancia 0–3 por solapamiento de habilidades |
| `run.py <variante>` | Embebe candidatos y vacantes con Ollama (`/api/embed`, con reintentos), calcula NDCG@10, P@5, coseno por nivel de relevancia y comportamiento entre idiomas. `SUB=1` usa el subconjunto de 56 candidatos |
| `bias.py <variante>` | Sondeo de sesgo por nombre (mismo perfil, distinto nombre) con *bootstrap* sobre candidatos |
| `cost2.py <modelos…>` | Coste por token y carga en frío, reiniciando el contenedor entre modelos |
| `resultados/*.json` | Resultados de la corrida del 14–15-nov-2026 (las matrices de vectores no se guardan) |

## Cómo repetirlo

```bash
docker run -d --name ollama -p 127.0.0.1:21434:11434 -v ollama:/root/.ollama ollama/ollama
for m in nomic-embed-text nomic-embed-text-v2-moe bge-m3 qwen3-embedding:0.6b embeddinggemma; do
  docker exec ollama ollama pull "$m"; done
python3 data.py && mkdir -p resultados
SUB=1 python3 run.py tfidf && SUB=1 python3 run.py qwen3-0.6b      # una variante por vez
SUB=1 python3 bias.py qwen3-0.6b                                    # requiere haber corrido run.py antes (usa sus vectores)
```

Variables útiles: `OLLAMA_URL` (por defecto `http://127.0.0.1:21434`). Dependencias: Python 3 y NumPy.

## Advertencias

- Cada modelo usa su **formato recomendado** (prefijos de tarea o instrucciones); ver el diccionario `V` de `run.py`.
- Para medir tiempos, usar una máquina que exponga **AVX2**; en una VM sin AVX las cifras son una cota pesimista y poco estables.
- Enviar `truncate=false` en integraciones reales (aquí se deja el valor por defecto solo para reproducir el comportamiento medido).
- Una corrida completa en CPU sin AVX tarda horas; usar un subconjunto o una máquina con AVX2.
