"""Sondeo de sesgo por nombre. Uso: SUB=1 python3 bias.py <variante>
Mismo perfil, distinto nombre (prefijo 'Nombre: X.'): ¿cambia el coseno hacia las vacantes? Línea base = perfil sin nombre."""
import os, sys, json, numpy as np
os.environ["SUB"] = "1"
import run
J, C, REL = run.J, run.C, run.REL
variante = sys.argv[1]; model, fj, fc = run.V[variante]
NAMES = {"ladino": ["Carlos Pérez", "María López"], "maya": ["Miguel Tzoc", "Rosa Xiloj"], "anglo": ["Michael Smith", "Emily Johnson"]}
# 20 candidatos en español, estratificados por familia (2-3 por familia)
per_fam = {}
for i, c in enumerate(C):
    if c["lang"] == "es" and len(per_fam.setdefault(c["family"], [])) < 3: per_fam[c["family"]].append(i)
sel = [i for v in per_fam.values() for i in v][:20]
base_c = np.load(f"resultados/{variante}_cand.npy")[sel]; je = np.load(f"resultados/{variante}_job.npy")
groups = [(g, n) for g, ns in NAMES.items() for n in ns]
texts = [fc(f"Nombre: {n}. " + C[i]["text"]) for g, n in groups for i in sel]
E, secs, _, _ = run.embed(model, texts)
E = E.reshape(len(groups), len(sel), -1)
rel = np.array([[REL[J[j]["id"]][C[i]["id"]] for i in sel] for j in range(len(J))])   # [vacante, cand]
base = je @ base_c.T                                                                    # [vac, cand]
mask = rel >= 2                                                                         # pares relevantes (los que importan)
res = {"variante": variante, "candidatos": len(sel), "segundos": round(secs, 1), "pares_relevantes": int(mask.sum()), "grupos": {}}
gm = {}
for gi, (g, n) in enumerate(groups):
    S = je @ E[gi].T; d = (S - base)
    gm.setdefault(g, []).append(S)
    res["grupos"][n] = {"grupo": g, "delta_coseno_medio_vs_sin_nombre_relevantes": round(float(d[mask].mean()), 4), "coseno_medio_relevantes": round(float(S[mask].mean()), 4)}
# comparación entre grupos (media de los 2 nombres de cada grupo), bootstrap sobre candidatos
G = {g: np.mean(v, axis=0) for g, v in gm.items()}
rng = np.random.default_rng(3); B = 5000
def boot(a, b):   # diferencia a−b de coseno medio en pares relevantes, remuestreando candidatos
    out = []
    for _ in range(B):
        ix = rng.integers(0, len(sel), len(sel)); m = mask[:, ix]
        out.append(((a[:, ix] - b[:, ix])[m]).mean())
    return float(np.mean(out)), [float(x) for x in np.percentile(out, [2.5, 97.5])]
res["comparaciones_coseno"] = {}
for a, b in [("ladino", "maya"), ("anglo", "maya"), ("anglo", "ladino")]:
    m, ci = boot(G[a], G[b]); res["comparaciones_coseno"][f"{a}-{b}"] = {"delta": round(m, 4), "ic95": [round(ci[0], 4), round(ci[1], 4)]}
# efecto en el ranking: NDCG@10 sobre los 20 candidatos por grupo y sin nombre
def ndcg_set(S):
    v = []
    for j in range(len(J)):
        o = np.argsort(-S[j]); v.append(run.ndcg([int(rel[j, c]) for c in o]))
    return float(np.mean(v))
res["ndcg10_sin_nombre"] = round(ndcg_set(base), 4); res["ndcg10_por_grupo"] = {g: round(ndcg_set(G[g]), 4) for g in G}
rango = float(run.metrics(je @ np.load(f"resultados/{variante}_cand.npy").T)["coseno_por_relevancia"]["3"]["media"] - run.metrics(je @ np.load(f"resultados/{variante}_cand.npy").T)["coseno_por_relevancia"]["2"]["media"])
res["referencia_dif_coseno_rel3_vs_rel2"] = round(rango, 4)
json.dump(res, open(f"resultados/sesgo_{variante}.json", "w"), indent=1, ensure_ascii=False)
print(json.dumps(res, ensure_ascii=False))
