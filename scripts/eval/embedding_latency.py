#!/usr/bin/env python3
"""Latencia de embeddings de Ollama frente al objetivo de frescura (TD-029, ADR-0017 decisión 10).

Solo usa la biblioteca estándar de Python. NO es parte de la aplicación.

Qué hace: comprueba que el equipo expone AVX2 (sin él, llama.cpp corre con SSE y es unas cien
veces más lento), mide la latencia de `/api/embed` con textos de varios tamaños, primero en frío
(el modelo se carga) y luego en caliente, y compara el peor caso con el presupuesto de
`frescura p95 <= 120 s` = latencia del embedding + ventana de agrupación de la cola.

Uso (en el equipo donde correrá Ollama en producción o su réplica):

    python3 -I scripts/eval/embedding_latency.py --host localhost --port 11434 \\
        --model qwen3-embedding:0.6b --repeats 20 --out latencia.json

Con `--num-thread N` se repite fijando los hilos, para ver si estabiliza los tiempos bajo una
cuota de CPU (cambiar `num_thread` recarga el modelo: la primera llamada de cada valor es en frío).

Salida: una tabla Markdown lista para pegar en `08-medicion-ollama-y-bakeoff-embeddings.md` y un
JSON con cada muestra. El código de salida es 0 si el p95 cabe en el presupuesto, 1 si no, 2 si el
equipo no expone AVX2 o Ollama no responde (en esos casos la medición no vale como cierre de TD-029).
"""
import argparse
import json
import math
import platform
import statistics
import sys
import time
import urllib.error
import urllib.request

# Frases en español de longitud conocida; se repiten hasta alcanzar el tamaño pedido. El contenido
# es sintético: la latencia depende de los tokens, no del significado.
BASE = (
    "Ingeniero de software con experiencia en Java, Spring Boot y PostgreSQL; "
    "diseño de servicios, pruebas automatizadas y despliegue con Docker. "
)
# Tamaños en caracteres: ~25, ~100 y ~400 tokens (≈ 4 caracteres por token en español).
SIZES = {"corto (~25 tokens)": 100, "medio (~100 tokens)": 400, "perfil (~400 tokens)": 1600}


def cpu_flags():
    """Los indicadores de CPU de Linux; en otros sistemas no se puede comprobar."""
    if platform.system() != "Linux":
        return None
    try:
        with open("/proc/cpuinfo", encoding="utf-8") as cpuinfo:
            for line in cpuinfo:
                if line.startswith("flags"):
                    return set(line.split(":", 1)[1].split())
    except OSError:
        return None
    return None


def text_of(chars):
    return (BASE * (chars // len(BASE) + 1))[:chars]


def embed(args, text, num_thread):
    body = {
        "model": args.model,
        "input": text,
        "truncate": False,
        "keep_alive": args.keep_alive,
        "options": {"num_ctx": args.num_ctx},
    }
    if num_thread:
        body["options"]["num_thread"] = num_thread
    request = urllib.request.Request(
        f"http://{args.host}:{args.port}/api/embed",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    start = time.monotonic()
    with urllib.request.urlopen(request, timeout=args.timeout) as response:
        payload = json.load(response)
    elapsed = time.monotonic() - start
    return elapsed, len(payload["embeddings"][0])


def percentile(values, q):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(q * len(ordered)) - 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=11434)
    parser.add_argument("--model", default="qwen3-embedding:0.6b")
    parser.add_argument("--num-ctx", type=int, default=1024)
    parser.add_argument("--keep-alive", default="30m")
    parser.add_argument("--repeats", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=300)
    parser.add_argument("--num-thread", type=int, action="append", default=[], help="repetible; sin él, el valor por defecto de Ollama")
    parser.add_argument("--budget", type=float, default=120, help="objetivo de frescura p95, en segundos")
    parser.add_argument("--window", type=float, default=30, help="ventana de agrupación de la cola, en segundos (jobseeker.matching.queue.window)")
    parser.add_argument("--allow-no-avx2", action="store_true", help="medir igualmente; el resultado NO sirve para cerrar TD-029")
    parser.add_argument("--out", help="archivo JSON con las muestras")
    args = parser.parse_args()

    flags = cpu_flags()
    avx2 = None if flags is None else ("avx2" in flags)
    print(f"Equipo: {platform.node()} · {platform.platform()}")
    print(f"AVX2: {'sí' if avx2 else 'NO' if avx2 is False else 'no se pudo comprobar'}", flush=True)
    if avx2 is False and not args.allow_no_avx2:
        print("Este equipo no expone AVX2: la medición no sirve como cierre de TD-029 (ADR-0026 lo exige). "
              "Use --allow-no-avx2 solo para ver el orden de magnitud.", file=sys.stderr)
        return 2

    threads = args.num_thread or [None]
    results = []
    for num_thread in threads:
        label = f"num_thread={num_thread}" if num_thread else "num_thread por defecto"
        for name, chars in SIZES.items():
            text = text_of(chars)
            samples = []
            try:
                for i in range(args.repeats + 1):
                    elapsed, dims = embed(args, text, num_thread)
                    samples.append(elapsed)
                    print(f"  {label} · {name} · {i}: {elapsed:.2f} s", flush=True)
            except (urllib.error.URLError, TimeoutError, OSError) as failure:
                print(f"Ollama no respondió ({failure}).", file=sys.stderr)
                return 2
            cold, warm = samples[0], samples[1:]
            results.append({"config": label, "size": name, "chars": chars, "dims": dims, "cold": cold, "warm": warm})

    print("\n| Configuración | Tamaño | En frío | p50 caliente | p95 caliente | Máx | Frescura p95 (p95 + ventana) |")
    print("|---|---|---|---|---|---|---|")
    worst = 0.0
    for r in results:
        p95 = percentile(r["warm"], 0.95)
        worst = max(worst, p95 + args.window)
        print(f"| {r['config']} | {r['size']} | {r['cold']:.2f} s | {statistics.median(r['warm']):.2f} s | {p95:.2f} s | {max(r['warm']):.2f} s | {p95 + args.window:.1f} s |")
    fits = worst <= args.budget
    print(f"\nPeor frescura p95 = {worst:.1f} s frente al objetivo de {args.budget:.0f} s (ventana {args.window:.0f} s): {'CABE' if fits else 'NO CABE'}")
    if args.out:
        with open(args.out, "w", encoding="utf-8") as out:
            json.dump({"avx2": avx2, "model": args.model, "budget": args.budget, "window": args.window, "results": results}, out, indent=1)
    return 0 if fits else 1


if __name__ == "__main__":
    sys.exit(main())
