import json, time, urllib.request, subprocess, sys, statistics as st
D = json.load(open("dataset.json")); txt = D["candidates"][0]["text"]
def call(model, text, to):
    body = json.dumps({"model": model, "input": [text], "keep_alive": "10m"}).encode()
    t = time.time(); r = json.load(urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:21434/api/embed", body, {"Content-Type": "application/json"}), timeout=to))
    return time.time() - t, r
out = {}
for m in sys.argv[1:]:
    subprocess.run(["docker", "restart", "jsspike-ollama"], capture_output=True); time.sleep(8)   # un solo modelo residente, CPU libre
    try:
        t0, r0 = call(m, txt, 280); w = []
        for _ in range(3): t, r = call(m, txt, 280); w.append(t)
        k = r["prompt_eval_count"]; med = st.median(w)
        out[m] = {"tokens": k, "primera_s(frio)": round(t0, 1), "carga_modelo_s": round(r0.get("load_duration", 0) / 1e9, 1), "caliente_s_mediana": round(med, 2), "ms_por_token": round(1000 * med / k, 1), "dim": len(r["embeddings"][0])}
    except Exception as e: out[m] = {"error": "sin respuesta en 280 s" if "timed out" in str(e) else str(e)[:60]}
    print(m, json.dumps(out[m], ensure_ascii=False), flush=True)
json.dump(out, open("resultados/costo_por_modelo.json", "w"), indent=1, ensure_ascii=False)
