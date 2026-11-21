"""Uso: python3 run.py <variante> ; guarda resultados/<variante>.json (métricas + tiempos reales de Ollama)."""
import json, sys, time, urllib.request, math, unicodedata, re, os
import numpy as np

URL = os.environ.get("OLLAMA_URL", "http://127.0.0.1:21434")
D = json.load(open("dataset.json")); C, J, REL = D["candidates"], D["jobs"], D["rel"]
if os.environ.get("SUB"):  # subconjunto: 7 candidatos por familia (5 ES + 2 EN) para acotar el costo en CPU sin AVX
    C = [c for c in C if int(c["id"].rsplit("_", 1)[1]) in (0, 1, 2, 3, 4, 7, 8)]

QWEN_I = "Instruct: Given a job posting, retrieve candidate profiles that fit the role\nQuery: "
# variante -> (modelo, formato_vacante, formato_candidato)
V = {
 "nomic-v15-sin_prefijo":  ("nomic-embed-text", lambda t: t, lambda t: t),
 "nomic-v15-asimetrico":   ("nomic-embed-text", lambda t: "search_query: " + t, lambda t: "search_document: " + t),
 "nomic-v15-simetrico":    ("nomic-embed-text", lambda t: "clustering: " + t, lambda t: "clustering: " + t),
 "nomic-v2moe-sin_prefijo": ("nomic-embed-text-v2-moe", lambda t: t, lambda t: t),
 "nomic-v2moe-asimetrico": ("nomic-embed-text-v2-moe", lambda t: "search_query: " + t, lambda t: "search_document: " + t),
 "bge-m3":                 ("bge-m3", lambda t: t, lambda t: t),
 "qwen3-0.6b":             ("qwen3-embedding:0.6b", lambda t: QWEN_I + t, lambda t: t),
 "embeddinggemma":         ("embeddinggemma", lambda t: "task: search result | query: " + t, lambda t: "title: none | text: " + t),
}

def embed(model, texts, bs=8):
    out, t_total, t_load, toks = [], 0, 0, 0
    for i in range(0, len(texts), bs):
        body = json.dumps({"model": model, "input": texts[i:i + bs], "truncate": True, "keep_alive": "30m"}).encode()
        for intento in range(4):  # un 500 transitorio de Ollama no debe tumbar toda la corrida; se registra el cuerpo del error
            try:
                r = json.load(urllib.request.urlopen(urllib.request.Request(URL + "/api/embed", body, {"Content-Type": "application/json"}), timeout=1800)); break
            except urllib.error.HTTPError as e:
                print("REINTENTO", intento + 1, "HTTP", e.code, e.read().decode()[:200], flush=True); time.sleep(10)
        else: raise RuntimeError("Ollama no respondió tras 4 intentos")
        out += r["embeddings"]; t_total += r.get("total_duration", 0); t_load += r.get("load_duration", 0); toks += r.get("prompt_eval_count", 0)
    m = np.array(out, dtype=np.float32); m /= np.linalg.norm(m, axis=1, keepdims=True)
    return m, t_total / 1e9, t_load / 1e9, toks

def ndcg(rels, k=10):
    g = lambda r: (2 ** r - 1)
    dcg = sum(g(r) / math.log2(i + 2) for i, r in enumerate(rels[:k]))
    ideal = sorted(rels, reverse=True); idcg = sum(g(r) / math.log2(i + 2) for i, r in enumerate(ideal[:k]))
    return dcg / idcg if idcg else 0.0

def metrics(S, tag=""):
    """S[j,c] = similitud. Devuelve NDCG@10, P@5 (rel>=2), y separación por nivel de relevancia."""
    res = {}
    def agg(sel):
        nd, p5 = [], []
        for ji in sel:
            order = np.argsort(-S[ji]); rels = [REL[J[ji]["id"]][C[c]["id"]] for c in order]
            nd.append(ndcg(rels)); p5.append(sum(1 for r in rels[:5] if r >= 2) / 5)
        return {"ndcg10": round(float(np.mean(nd)), 4), "p5": round(float(np.mean(p5)), 4), "n_vacantes": len(sel)}
    res["global"] = agg(range(len(J)))
    for l in ("es", "en"): res["vacante_" + l] = agg([i for i, j in enumerate(J) if j["lang"] == l])
    lev = {k: [] for k in range(4)}
    for ji, j in enumerate(J):
        for ci, c in enumerate(C): lev[REL[j["id"]][c["id"]]].append(S[ji, ci])
    res["coseno_por_relevancia"] = {str(k): {"media": round(float(np.mean(v)), 4), "min": round(float(np.min(v)), 4), "max": round(float(np.max(v)), 4)} for k, v in lev.items()}
    # idioma cruzado: vacante ES vs candidatos EN y viceversa (misma familia, relevancia >=2)
    cross, same = [], []
    for ji, j in enumerate(J):
        for ci, c in enumerate(C):
            if REL[j["id"]][c["id"]] >= 2: (same if j["lang"] == c["lang"] else cross).append(S[ji, ci])
    res["coseno_relevantes_mismo_idioma"] = round(float(np.mean(same)), 4); res["coseno_relevantes_idioma_cruzado"] = round(float(np.mean(cross)), 4)
    return res

def strip(t): return re.sub(r"[^a-z0-9 ]", " ", unicodedata.normalize("NFD", t.lower()).encode("ascii", "ignore").decode())

def tfidf():
    docs = [strip(x["text"]).split() for x in C + J]; N = len(docs); df = {}
    for d in docs:
        for w in set(d): df[w] = df.get(w, 0) + 1
    vocab = {w: i for i, w in enumerate(df)}; M = np.zeros((N, len(vocab)), dtype=np.float32)
    for r, d in enumerate(docs):
        for w in d: M[r, vocab[w]] += 1
        M[r] *= np.array([math.log((1 + N) / (1 + df[w])) + 1 for w in vocab], dtype=np.float32) if False else 1
    idf = np.array([math.log((1 + N) / (1 + df[w])) + 1 for w in vocab], dtype=np.float32); M *= idf
    M /= np.linalg.norm(M, axis=1, keepdims=True) + 1e-9
    return M[len(C):] @ M[:len(C)].T

if __name__ == "__main__":
    name = sys.argv[1]; os.makedirs("resultados", exist_ok=True)
    if name == "tfidf":
        json.dump({"variante": name, **metrics(tfidf())}, open("resultados/tfidf.json", "w"), indent=1, ensure_ascii=False); print("tfidf listo"); sys.exit()
    model, fj, fc = V[name]; t0 = time.time()
    ce, tc, lc, kc = embed(model, [fc(c["text"]) for c in C]); je, tj, lj, kj = embed(model, [fj(j["text"]) for j in J])
    S = je @ ce.T; wall = time.time() - t0; n = len(C) + len(J)
    out = {"variante": name, "modelo": model, "dim": int(ce.shape[1]), "textos": n, "tokens_prompt_total": kc + kj, "tokens_medios": round((kc + kj) / n, 1),
           "segundos_pared": round(wall, 1), "ms_por_texto": round(1000 * wall / n, 1), "segundos_carga_modelo": round(lc + lj, 2), **metrics(S)}
    np.save(f"resultados/{name}_cand.npy", ce); np.save(f"resultados/{name}_job.npy", je)
    json.dump(out, open(f"resultados/{name}.json", "w"), indent=1, ensure_ascii=False)
    print(name, "dim", out["dim"], "| NDCG@10 %.3f P@5 %.3f | %.0f ms/texto | tokens medios %.0f" % (out["global"]["ndcg10"], out["global"]["p5"], out["ms_por_texto"], out["tokens_medios"]))
