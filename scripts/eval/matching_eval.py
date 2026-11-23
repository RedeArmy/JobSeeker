#!/usr/bin/env python3
"""Arnés de medición del matching: cobertura de ESCO y calibración del score semántico.

Es una herramienta de medición, no parte de la aplicación: lee archivos CSV, calcula cifras con su
intervalo de confianza y las imprime. Solo usa la biblioteca estándar, para no abrir una decisión de
dependencias (ADR-0017 y ADR-0019 piden estas mediciones antes de fijar umbrales).

IMPORTANTE: lo que esta herramienta imprime vale lo que valgan los datos que se le dan. Con datos
sintéticos solo demuestra que la herramienta funciona; no es evidencia sobre el sistema (ADR-0017,
controles C2 y C4; ADR-0019, controles D3).

Uso (con el intérprete aislado, como el resto de scripts del repositorio):

    python3 -I scripts/eval/matching_eval.py coverage --esco esco_skills_es.csv --skills habilidades.csv
    python3 -I scripts/eval/matching_eval.py calibrate --pairs pares_etiquetados.csv
"""

import argparse
import csv
import math
import random
import re
import sys
import unicodedata
from collections import Counter, defaultdict

Z95 = 1.959963984540054


# ---------------------------------------------------------------------------
# Estadística básica
# ---------------------------------------------------------------------------


def wilson_interval(successes, total, z=Z95):
    """Intervalo de Wilson de una proporción. Se usa en vez del normal porque no se sale de [0, 1] ni
    se degrada con muestras pequeñas o proporciones extremas, que es lo que habrá aquí."""
    if total <= 0:
        return (0.0, 1.0)
    p = successes / total
    denom = 1 + z * z / total
    centre = (p + z * z / (2 * total)) / denom
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denom
    return (max(0.0, centre - half), min(1.0, centre + half))


def percentile(sorted_values, q):
    """Percentil por interpolación lineal de una lista ya ordenada."""
    if not sorted_values:
        return float("nan")
    pos = (len(sorted_values) - 1) * q
    lo, hi = int(math.floor(pos)), int(math.ceil(pos))
    if lo == hi:
        return sorted_values[lo]
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (pos - lo)


def sigmoid(x):
    # Dos ramas para no desbordar exp con argumentos muy negativos o muy positivos.
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


# ---------------------------------------------------------------------------
# Calibración: Platt e isotónica
# ---------------------------------------------------------------------------


def fit_platt(scores, labels, iterations=100, ridge=1e-6):
    """Ajusta p = sigmoide(a*s + c) por Newton-Raphson. Devuelve (a, c).

    La penalización `ridge` evita que los parámetros diverjan si las clases son separables, caso
    habitual con muestras pequeñas."""
    a, c = 0.0, 0.0
    for _ in range(iterations):
        g_a = g_c = 0.0
        h_aa = h_ac = h_cc = 0.0
        for s, y in zip(scores, labels):
            p = sigmoid(a * s + c)
            err = p - y
            w = p * (1 - p)
            g_a += err * s
            g_c += err
            h_aa += w * s * s
            h_ac += w * s
            h_cc += w
        g_a += ridge * a
        h_aa += ridge
        h_cc += ridge
        det = h_aa * h_cc - h_ac * h_ac
        if abs(det) < 1e-18:
            break
        step_a = (h_cc * g_a - h_ac * g_c) / det
        step_c = (h_aa * g_c - h_ac * g_a) / det
        a -= step_a
        c -= step_c
        if abs(step_a) < 1e-10 and abs(step_c) < 1e-10:
            break
    return a, c


def fit_isotonic(scores, labels):
    """Regresión isotónica por agrupación de adyacentes (PAV). Devuelve puntos (x, y) crecientes."""
    pairs = sorted(zip(scores, labels))
    blocks = [[x, float(y), 1.0] for x, y in pairs]  # [x máximo del bloque, media, peso]
    merged = []
    for block in blocks:
        merged.append(block)
        while len(merged) >= 2 and merged[-2][1] >= merged[-1][1]:
            right = merged.pop()
            left = merged.pop()
            weight = left[2] + right[2]
            mean = (left[1] * left[2] + right[1] * right[2]) / weight
            merged.append([right[0], mean, weight])
    return [(b[0], b[1]) for b in merged]


def predict_isotonic(points, s):
    if not points:
        return 0.5
    if s <= points[0][0]:
        return points[0][1]
    if s >= points[-1][0]:
        return points[-1][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if x0 <= s <= x1:
            return y0 if x1 == x0 else y0 + (y1 - y0) * (s - x0) / (x1 - x0)
    return points[-1][1]


def brier(probs, labels):
    return sum((p - y) ** 2 for p, y in zip(probs, labels)) / len(labels)


def log_loss(probs, labels, eps=1e-12):
    total = 0.0
    for p, y in zip(probs, labels):
        p = min(max(p, eps), 1 - eps)
        total -= y * math.log(p) + (1 - y) * math.log(1 - p)
    return total / len(labels)


def expected_calibration_error(probs, labels, bins=10):
    """Diferencia media entre la probabilidad prometida y la frecuencia observada, por tramos."""
    buckets = defaultdict(list)
    for p, y in zip(probs, labels):
        buckets[min(int(p * bins), bins - 1)].append((p, y))
    total = len(labels)
    ece = 0.0
    for items in buckets.values():
        mean_p = sum(p for p, _ in items) / len(items)
        freq = sum(y for _, y in items) / len(items)
        ece += len(items) / total * abs(mean_p - freq)
    return ece


def auc(scores, labels):
    """Área bajo la curva ROC por rangos (Mann-Whitney), con rango promedio en los empates."""
    order = sorted(range(len(scores)), key=lambda i: scores[i])
    ranks = [0.0] * len(scores)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and scores[order[j + 1]] == scores[order[i]]:
            j += 1
        average = (i + j) / 2 + 1
        for k in range(i, j + 1):
            ranks[order[k]] = average
        i = j + 1
    positives = sum(labels)
    negatives = len(labels) - positives
    if positives == 0 or negatives == 0:
        return float("nan")
    rank_sum = sum(r for r, y in zip(ranks, labels) if y == 1)
    return (rank_sum - positives * (positives + 1) / 2) / (positives * negatives)


# ---------------------------------------------------------------------------
# Métricas de ranking por vacante
# ---------------------------------------------------------------------------


def precision_at_k(ranked_grades, k, relevant_from=2):
    top = ranked_grades[:k]
    return sum(1 for g in top if g >= relevant_from) / k if k > 0 else 0.0


def ndcg_at_k(ranked_grades, k):
    """NDCG con ganancia 2^g - 1; 1.0 si el orden es el ideal. Una vacante sin ningún par relevante
    no puede puntuar y se devuelve NaN para que quien agregue la excluya y no la cuente como cero."""
    def dcg(grades):
        return sum((2 ** g - 1) / math.log2(i + 2) for i, g in enumerate(grades[:k]))

    ideal = dcg(sorted(ranked_grades, reverse=True))
    return float("nan") if ideal == 0 else dcg(ranked_grades) / ideal


def mean_without_nan(values):
    clean = [v for v in values if not math.isnan(v)]
    return sum(clean) / len(clean) if clean else float("nan")


def ranking_metrics(rows, k):
    """P@K y NDCG@K medios por vacante, ordenando por la columna `score`."""
    by_vacancy = defaultdict(list)
    for row in rows:
        by_vacancy[row["vacancy_id"]].append(row)
    precisions, ndcgs = [], []
    for items in by_vacancy.values():
        ranked = sorted(items, key=lambda r: -r["score"])
        grades = [r["label"] for r in ranked]
        if len(grades) < k:
            continue  # una vacante con menos de K pares no mide lo mismo; se excluye y se informa
        precisions.append(precision_at_k(grades, k))
        ndcgs.append(ndcg_at_k(grades, k))
    return mean_without_nan(precisions), mean_without_nan(ndcgs), len(precisions)


# ---------------------------------------------------------------------------
# Validación agrupada por vacante y bootstrap
# ---------------------------------------------------------------------------


def grouped_folds(vacancy_ids, folds, seed):
    """Reparte las VACANTES, no los pares, entre los pliegues: los pares de una misma vacante
    comparten verdad-terreno y, si cayeran a ambos lados, la evaluación saldría inflada (ADR-0017,
    control C2)."""
    unique = sorted(set(vacancy_ids))
    random.Random(seed).shuffle(unique)
    assignment = {}
    for index, vacancy in enumerate(unique):
        assignment[vacancy] = index % folds
    return assignment


def out_of_fold_probabilities(rows, folds, seed, method):
    """Probabilidades de relevancia de cada par, predichas por un modelo que NO vio su vacante."""
    assignment = grouped_folds([r["vacancy_id"] for r in rows], folds, seed)
    probs = [None] * len(rows)
    skipped = 0
    for fold in range(folds):
        train = [r for r in rows if assignment[r["vacancy_id"]] != fold]
        test_index = [i for i, r in enumerate(rows) if assignment[r["vacancy_id"]] == fold]
        labels = [r["relevant"] for r in train]
        if len(set(labels)) < 2 or not test_index:
            skipped += 1
            continue
        scores = [r["cosine"] for r in train]
        if method == "platt":
            a, c = fit_platt(scores, labels)
            for i in test_index:
                probs[i] = sigmoid(a * rows[i]["cosine"] + c)
        else:
            points = fit_isotonic(scores, labels)
            for i in test_index:
                probs[i] = predict_isotonic(points, rows[i]["cosine"])
    return probs, skipped


def bootstrap_by_vacancy(rows, statistic, repetitions, seed):
    """Intervalo percentil del 95 % remuestreando VACANTES con reemplazo (la unidad independiente).

    Cada extracción de una vacante recibe un identificador propio: si una vacante sale dos veces,
    sus pares no deben fusionarse en un solo grupo, porque el ranking se calcula por vacante."""
    by_vacancy = defaultdict(list)
    for row in rows:
        by_vacancy[row["vacancy_id"]].append(row)
    keys = sorted(by_vacancy)
    rng = random.Random(seed)
    values = []
    for _ in range(repetitions):
        sample = []
        for draw in range(len(keys)):
            chosen = rng.choice(keys)
            for row in by_vacancy[chosen]:
                copy = dict(row)
                copy["vacancy_id"] = (chosen, draw)
                sample.append(copy)
        value = statistic(sample)
        if not math.isnan(value):
            values.append(value)
    values.sort()
    return percentile(values, 0.025), percentile(values, 0.975)


def read_pairs(path):
    """Lee pares etiquetados: vacancy_id, candidate_id, cosine, label (0-3) y, opcional, score."""
    rows = []
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        missing = {"vacancy_id", "candidate_id", "cosine", "label"} - set(reader.fieldnames or [])
        if missing:
            raise SystemExit("faltan columnas en %s: %s" % (path, ", ".join(sorted(missing))))
        for line, raw in enumerate(reader, start=2):
            try:
                label = int(raw["label"])
                cosine = float(raw["cosine"])
                score = float(raw["score"]) if raw.get("score") not in (None, "") else cosine
            except ValueError:
                raise SystemExit("línea %d de %s: valor no numérico" % (line, path))
            if label not in (0, 1, 2, 3) or not math.isfinite(cosine) or not math.isfinite(score):
                raise SystemExit("línea %d de %s: etiqueta fuera de 0-3 o valor no finito" % (line, path))
            rows.append(
                {
                    "vacancy_id": raw["vacancy_id"],
                    "candidate_id": raw["candidate_id"],
                    "cosine": cosine,
                    "label": label,
                    "relevant": 1 if label >= 2 else 0,
                    "score": score,
                }
            )
    return rows


def run_calibration(rows, folds, repetitions, seed, k):
    """Devuelve un diccionario con todas las cifras, para imprimirlo o probarlo."""
    vacancies = len({r["vacancy_id"] for r in rows})
    positives = sum(r["relevant"] for r in rows)
    result = {
        "pairs": len(rows),
        "vacancies": vacancies,
        "positives": positives,
        "warnings": [],
        "methods": {},
    }
    if vacancies < folds:
        result["warnings"].append(
            "hay menos vacantes (%d) que pliegues (%d): la validación agrupada no es posible" % (vacancies, folds)
        )
        return result
    if vacancies < 30:
        result["warnings"].append(
            "con %d vacantes los intervalos serán anchos: el protocolo pide al menos 30 (ADR-0017, C2)" % vacancies
        )
    labels = [r["relevant"] for r in rows]
    for method in ("platt", "isotonic"):
        probs, skipped = out_of_fold_probabilities(rows, folds, seed, method)
        usable = [i for i, p in enumerate(probs) if p is not None]
        if not usable:
            result["warnings"].append("%s: ningún pliegue tenía ambas clases" % method)
            continue
        sub_probs = [probs[i] for i in usable]
        sub_labels = [labels[i] for i in usable]
        scored = [dict(rows[i], prob=probs[i]) for i in usable]

        def stat_brier(sample):
            return brier([r["prob"] for r in sample], [r["relevant"] for r in sample])

        result["methods"][method] = {
            "brier": brier(sub_probs, sub_labels),
            "brier_ci": bootstrap_by_vacancy(scored, stat_brier, repetitions, seed),
            "log_loss": log_loss(sub_probs, sub_labels),
            "ece": expected_calibration_error(sub_probs, sub_labels),
            "auc": auc([rows[i]["cosine"] for i in usable], sub_labels),
            "folds_skipped": skipped,
        }
    baseline = sum(labels) / len(labels)
    result["baseline_brier"] = brier([baseline] * len(labels), labels)

    # Ranking: no depende de la calibración (una función monótona no cambia el orden) sino de la
    # columna `score` que se entregue; por eso se informa aparte.
    def stat_precision(sample):
        return ranking_metrics(sample, k)[0]

    def stat_ndcg(sample):
        return ranking_metrics(sample, k)[1]

    precision, ndcg, used = ranking_metrics(rows, k)
    result["ranking"] = {
        "k": k,
        "vacancies_used": used,
        "precision": precision,
        "precision_ci": bootstrap_by_vacancy(rows, stat_precision, repetitions, seed),
        "ndcg": ndcg,
        "ndcg_ci": bootstrap_by_vacancy(rows, stat_ndcg, repetitions, seed),
    }
    return result


# ---------------------------------------------------------------------------
# Cobertura de ESCO
# ---------------------------------------------------------------------------

_SYMBOLS = {"+": " plus ", "#": " sharp "}


def normalize(text):
    """Forma normalizada de una habilidad: minúsculas, sin acentos, símbolos con significado
    deletreados (c++ no puede igualar a c) y sin otra puntuación (ADR-0019, decisión 5)."""
    decomposed = unicodedata.normalize("NFKD", text)
    stripped = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    lowered = stripped.lower()
    for symbol, word in _SYMBOLS.items():
        lowered = lowered.replace(symbol, word)
    lowered = re.sub(r"[^a-z0-9]+", " ", lowered)
    return re.sub(r"\s+", " ", lowered).strip()


def read_esco(path):
    """Lee un CSV de habilidades de ESCO: conceptUri, preferredLabel, altLabels, hiddenLabels.
    Devuelve etiqueta normalizada -> conjunto de conceptos que la tienen."""
    index = defaultdict(set)
    concepts = set()
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        missing = {"conceptUri", "preferredLabel"} - set(reader.fieldnames or [])
        if missing:
            raise SystemExit("faltan columnas en %s: %s" % (path, ", ".join(sorted(missing))))
        for raw in reader:
            uri = raw["conceptUri"]
            concepts.add(uri)
            labels = [raw["preferredLabel"]]
            for column in ("altLabels", "hiddenLabels"):
                labels.extend(re.split(r"[\n|]", raw.get(column) or ""))
            for label in labels:
                key = normalize(label)
                if key:
                    index[key].add(uri)
    return index, len(concepts)


def read_skills(path):
    """Lee las habilidades a medir: una columna `skill` y, opcional, `source_id` (qué CV o anuncio)."""
    skills = []
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if "skill" not in (reader.fieldnames or []):
            raise SystemExit("falta la columna `skill` en %s" % path)
        for raw in reader:
            text = (raw["skill"] or "").strip()
            if text:
                skills.append((text, raw.get("source_id") or ""))
    return skills


def classify(index, skill):
    """Los dos primeros escalones del ADR-0019 (el de embeddings sigue apagado): etiqueta exacta de
    un solo concepto, etiqueta ambigua (varios conceptos) o sin enlace."""
    concepts = index.get(normalize(skill), set())
    if len(concepts) == 1:
        return "UNIQUE"
    if len(concepts) > 1:
        return "AMBIGUOUS"
    return "NONE"


def run_coverage(index, skills, top):
    """Cobertura sobre habilidades DISTINTAS (una habilidad muy repetida no debe inflar la cifra) y
    sobre las menciones totales, que es lo que un usuario percibe."""
    distinct = {}
    mentions = Counter()
    for text, _ in skills:
        key = normalize(text)
        if key:
            mentions[key] += 1
            distinct.setdefault(key, text)
    counts_distinct = Counter(classify(index, text) for text in distinct.values())
    counts_mentions = Counter()
    for key, text in distinct.items():
        counts_mentions[classify(index, text)] += mentions[key]
    sources = len({source for _, source in skills if source})
    unmatched = sorted(
        ((mentions[key], text) for key, text in distinct.items() if classify(index, text) == "NONE"),
        key=lambda item: (-item[0], item[1]),
    )[:top]
    total_distinct = sum(counts_distinct.values())
    total_mentions = sum(counts_mentions.values())
    return {
        "distinct": total_distinct,
        "mentions": total_mentions,
        "sources": sources,
        "by_distinct": counts_distinct,
        "by_mentions": counts_mentions,
        "ci_unique_distinct": wilson_interval(counts_distinct["UNIQUE"], total_distinct),
        "ci_none_distinct": wilson_interval(counts_distinct["NONE"], total_distinct),
        "unmatched_top": unmatched,
        "ambiguous": sorted(text for key, text in distinct.items() if classify(index, text) == "AMBIGUOUS"),
    }


# ---------------------------------------------------------------------------
# Salida
# ---------------------------------------------------------------------------


def pct(value):
    return "n/d" if value is None or (isinstance(value, float) and math.isnan(value)) else "%.1f %%" % (100 * value)


def num(value):
    return "n/d" if value is None or (isinstance(value, float) and math.isnan(value)) else "%.3f" % value


def print_coverage(report, concepts):
    d, m = report["by_distinct"], report["by_mentions"]
    print("Cobertura de ESCO (%d conceptos en el archivo)" % concepts)
    print("  habilidades distintas: %d | menciones: %d | fuentes: %d" % (report["distinct"], report["mentions"], report["sources"]))
    low, high = report["ci_unique_distinct"]
    print("  enlazadas por etiqueta exacta única: %s (IC 95 %%: %s a %s)" % (pct(d["UNIQUE"] / report["distinct"]) if report["distinct"] else "n/d", pct(low), pct(high)))
    print("  ambiguas (varios conceptos):         %s" % (pct(d["AMBIGUOUS"] / report["distinct"]) if report["distinct"] else "n/d"))
    low, high = report["ci_none_distinct"]
    print("  sin enlace (texto libre):            %s (IC 95 %%: %s a %s)" % (pct(d["NONE"] / report["distinct"]) if report["distinct"] else "n/d", pct(low), pct(high)))
    print("  por menciones (lo que ve el usuario): únicas %s | ambiguas %s | sin enlace %s" % (
        pct(m["UNIQUE"] / report["mentions"]) if report["mentions"] else "n/d",
        pct(m["AMBIGUOUS"] / report["mentions"]) if report["mentions"] else "n/d",
        pct(m["NONE"] / report["mentions"]) if report["mentions"] else "n/d"))
    if report["distinct"] < 250:
        print("  AVISO: con %d habilidades distintas el intervalo es ancho; unas 250 a 300 dan unos ±5 puntos." % report["distinct"])
    if report["unmatched_top"]:
        print("  sin enlace más frecuentes (candidatas a habilidades locales):")
        for count, text in report["unmatched_top"]:
            print("    %4d  %s" % (count, text))
    if report["ambiguous"]:
        print("  ambiguas (nunca se enlazan solas): " + ", ".join(report["ambiguous"][:20]))


def print_calibration(result):
    print("Calibración: %d pares, %d vacantes, %d relevantes" % (result["pairs"], result["vacancies"], result["positives"]))
    for warning in result["warnings"]:
        print("  AVISO: " + warning)
    if not result["methods"]:
        return
    print("  Brier de referencia (siempre la tasa base): %s" % num(result["baseline_brier"]))
    for method, values in result["methods"].items():
        low, high = values["brier_ci"]
        print("  %-8s Brier %s (IC 95 %% %s a %s) | pérdida log %s | ECE %s | AUC %s" % (
            method, num(values["brier"]), num(low), num(high), num(values["log_loss"]), num(values["ece"]), num(values["auc"])))
    ranking = result["ranking"]
    print("  Ranking sobre la columna `score` (%d vacantes con al menos %d pares):" % (ranking["vacancies_used"], ranking["k"]))
    print("    P@%d  %s (IC 95 %% %s a %s)" % (ranking["k"], num(ranking["precision"]), num(ranking["precision_ci"][0]), num(ranking["precision_ci"][1])))
    print("    NDCG@%d %s (IC 95 %% %s a %s)" % (ranking["k"], num(ranking["ndcg"]), num(ranking["ndcg_ci"][0]), num(ranking["ndcg_ci"][1])))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    cov = sub.add_parser("coverage", help="cobertura de ESCO sobre un archivo de habilidades")
    cov.add_argument("--esco", required=True, help="CSV de habilidades de ESCO (conceptUri, preferredLabel, altLabels, hiddenLabels)")
    cov.add_argument("--skills", required=True, help="CSV con la columna `skill` y, opcional, `source_id`")
    cov.add_argument("--top", type=int, default=30)
    cal = sub.add_parser("calibrate", help="calibración y ranking sobre pares etiquetados")
    cal.add_argument("--pairs", required=True, help="CSV: vacancy_id, candidate_id, cosine, label (0-3) y, opcional, score")
    cal.add_argument("--folds", type=int, default=5)
    cal.add_argument("--bootstrap", type=int, default=1000)
    cal.add_argument("--seed", type=int, default=7)
    cal.add_argument("--k", type=int, default=10)
    args = parser.parse_args(argv)
    if args.command == "coverage":
        index, concepts = read_esco(args.esco)
        print_coverage(run_coverage(index, read_skills(args.skills), args.top), concepts)
    else:
        print_calibration(run_calibration(read_pairs(args.pairs), args.folds, args.bootstrap, args.seed, args.k))
    return 0


if __name__ == "__main__":
    sys.exit(main())
