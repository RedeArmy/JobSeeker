"""Pruebas del arnés de medición del matching.

Los datos son SINTÉTICOS y con verdad conocida: sirven para demostrar que la herramienta recupera
lo que debe y que sus intervalos se comportan, no para decir nada del sistema real.

Se ejecutan con el intérprete aislado, como el resto de scripts:

    python3 -I -m unittest discover -s scripts/eval/tests -t scripts/eval/tests
"""

import contextlib
import importlib.util
import io
import math
import os
import random
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("matching_eval", os.path.join(_HERE, "..", "matching_eval.py"))
me = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(me)


def synthetic_pairs(vacancies, per_vacancy, seed, mu0=0.55, mu1=0.70, sigma=0.08):
    """Pares con dos clases normales: la probabilidad real de ser relevante dado el coseno es
    logística con a = (mu1 - mu0) / sigma^2 y punto medio (mu0 + mu1) / 2."""
    rng = random.Random(seed)
    rows = []
    for v in range(vacancies):
        for c in range(per_vacancy):
            relevant = 1 if rng.random() < 0.5 else 0
            cosine = rng.gauss(mu1 if relevant else mu0, sigma)
            label = rng.choice([2, 3]) if relevant else rng.choice([0, 1])
            rows.append(
                {
                    "vacancy_id": "v%d" % v,
                    "candidate_id": "c%d" % c,
                    "cosine": cosine,
                    "label": label,
                    "relevant": relevant,
                    "score": cosine,
                }
            )
    return rows


def write_csv(path, header, rows):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(",".join(header) + "\n")
        for row in rows:
            handle.write(",".join(str(x) for x in row) + "\n")


class StatisticsTest(unittest.TestCase):
    def test_wilson_matches_the_known_values(self):
        low, high = me.wilson_interval(5, 10)
        self.assertAlmostEqual(low, 0.2366, places=3)
        self.assertAlmostEqual(high, 0.7634, places=3)
        low, high = me.wilson_interval(0, 10)
        self.assertEqual(low, 0.0)
        self.assertAlmostEqual(high, 0.2775, places=3)

    def test_wilson_with_no_data_is_the_whole_range(self):
        self.assertEqual(me.wilson_interval(0, 0), (0.0, 1.0))

    def test_wilson_never_leaves_zero_to_one(self):
        for successes in (0, 1, 9, 10):
            low, high = me.wilson_interval(successes, 10)
            self.assertGreaterEqual(low, 0.0)
            self.assertLessEqual(high, 1.0)

    def test_percentile_interpolates(self):
        self.assertEqual(me.percentile([1, 2, 3, 4, 5], 0.5), 3)
        self.assertAlmostEqual(me.percentile([0, 10], 0.25), 2.5)
        self.assertTrue(math.isnan(me.percentile([], 0.5)))

    def test_sigmoid_does_not_overflow(self):
        self.assertEqual(me.sigmoid(1000), 1.0)
        self.assertEqual(me.sigmoid(-1000), 0.0)
        self.assertAlmostEqual(me.sigmoid(0), 0.5)

    def test_auc_of_a_known_case(self):
        self.assertAlmostEqual(me.auc([0.1, 0.4, 0.35, 0.8], [0, 0, 1, 1]), 0.75)

    def test_auc_with_ties_uses_average_ranks(self):
        self.assertAlmostEqual(me.auc([1, 1, 1, 1], [0, 1, 0, 1]), 0.5)

    def test_auc_with_one_class_is_undefined(self):
        self.assertTrue(math.isnan(me.auc([0.1, 0.2], [1, 1])))

    def test_brier_and_log_loss_of_perfect_and_worst_cases(self):
        self.assertEqual(me.brier([1.0, 0.0], [1, 0]), 0.0)
        self.assertEqual(me.brier([0.0, 1.0], [1, 0]), 1.0)
        self.assertAlmostEqual(me.log_loss([0.5, 0.5], [1, 0]), math.log(2))
        self.assertGreater(me.log_loss([0.0], [1]), 20)  # recortado, no infinito

    def test_ece_is_zero_when_probabilities_match_frequencies(self):
        self.assertAlmostEqual(me.expected_calibration_error([0.5] * 10, [1] * 5 + [0] * 5), 0.0)

    def test_ece_detects_overconfidence(self):
        self.assertAlmostEqual(me.expected_calibration_error([0.95] * 10, [1] * 5 + [0] * 5), 0.45)


class CalibrationTest(unittest.TestCase):
    def test_platt_recovers_the_true_logistic(self):
        rows = synthetic_pairs(vacancies=600, per_vacancy=10, seed=11)
        a, c = me.fit_platt([r["cosine"] for r in rows], [r["relevant"] for r in rows])
        true_a = 0.15 / 0.08**2
        self.assertAlmostEqual(a / true_a, 1.0, delta=0.08)
        self.assertAlmostEqual(-c / a, 0.625, delta=0.01)

    def test_platt_does_not_diverge_on_separable_data(self):
        a, c = me.fit_platt([0.1, 0.2, 0.8, 0.9], [0, 0, 1, 1])
        self.assertTrue(math.isfinite(a) and math.isfinite(c))
        self.assertGreater(me.sigmoid(a * 0.9 + c), 0.9)
        self.assertLess(me.sigmoid(a * 0.1 + c), 0.1)

    def test_isotonic_merges_violations_and_is_monotone(self):
        points = me.fit_isotonic([1, 2, 3, 4], [0, 1, 0, 1])
        self.assertEqual(points, [(1, 0.0), (3, 0.5), (4, 1.0)])
        self.assertAlmostEqual(me.predict_isotonic(points, 2), 0.25)
        ys = [y for _, y in points]
        self.assertEqual(ys, sorted(ys))

    def test_isotonic_clamps_outside_the_range(self):
        points = me.fit_isotonic([1, 2, 3, 4], [0, 1, 0, 1])
        self.assertEqual(me.predict_isotonic(points, -5), 0.0)
        self.assertEqual(me.predict_isotonic(points, 99), 1.0)
        self.assertEqual(me.predict_isotonic([], 0.3), 0.5)

    def test_folds_assign_every_pair_of_a_vacancy_to_one_fold_and_stay_balanced(self):
        ids = ["v%d" % (i // 10) for i in range(500)]
        assignment = me.grouped_folds(ids, 5, seed=3)
        self.assertEqual(set(assignment), set(ids))
        sizes = [list(assignment.values()).count(f) for f in range(5)]
        self.assertLessEqual(max(sizes) - min(sizes), 1)

    def test_out_of_fold_predictions_never_use_the_vacancy_being_predicted(self):
        # Cada vacante tiene una "marca" que solo aparece en ella: un modelo que viera su propia
        # vacante al predecirla la memorizaría; con pliegues por vacante no puede.
        rng = random.Random(5)
        rows = []
        for v in range(40):
            tell = rng.random()
            for c in range(10):
                rows.append({"vacancy_id": "v%d" % v, "candidate_id": "c%d" % c, "cosine": tell,
                             "label": 0, "relevant": rng.randint(0, 1), "score": tell})
        probs, _ = me.out_of_fold_probabilities(rows, 5, seed=1, method="isotonic")
        labels = [r["relevant"] for r in rows]
        # Con etiquetas aleatorias respecto a la marca, un modelo honesto no puede predecir mejor que
        # el azar: su Brier no baja del de la tasa base.
        base = sum(labels) / len(labels)
        self.assertGreaterEqual(me.brier([p for p in probs], labels), me.brier([base] * len(labels), labels) - 0.01)

    def test_a_calibrated_model_beats_the_base_rate_on_separable_classes(self):
        result = me.run_calibration(synthetic_pairs(60, 10, seed=2), folds=5, repetitions=200, seed=4, k=10)
        self.assertGreater(result["methods"]["platt"]["auc"], 0.85)
        self.assertLess(result["methods"]["platt"]["brier"], result["baseline_brier"])
        self.assertLess(result["methods"]["platt"]["ece"], 0.1)

    def test_the_interval_contains_the_estimate_and_narrows_with_more_vacancies(self):
        small = me.run_calibration(synthetic_pairs(30, 10, seed=8), 5, 300, 4, 10)["methods"]["platt"]
        large = me.run_calibration(synthetic_pairs(200, 10, seed=8), 5, 300, 4, 10)["methods"]["platt"]
        for values in (small, large):
            self.assertLessEqual(values["brier_ci"][0], values["brier"])
            self.assertGreaterEqual(values["brier_ci"][1], values["brier"])
        self.assertLess(large["brier_ci"][1] - large["brier_ci"][0], small["brier_ci"][1] - small["brier_ci"][0])

    def test_too_few_vacancies_for_the_folds_stops_with_a_warning(self):
        result = me.run_calibration(synthetic_pairs(3, 10, seed=1), folds=5, repetitions=10, seed=1, k=10)
        self.assertEqual(result["methods"], {})
        self.assertTrue(any("menos vacantes" in w for w in result["warnings"]))

    def test_fewer_than_thirty_vacancies_warns_that_the_intervals_are_wide(self):
        result = me.run_calibration(synthetic_pairs(12, 10, seed=1), folds=5, repetitions=20, seed=1, k=10)
        self.assertTrue(any("al menos 30" in w for w in result["warnings"]))

    def test_the_same_seed_gives_the_same_numbers(self):
        a = me.run_calibration(synthetic_pairs(40, 10, seed=6), 5, 100, 9, 10)
        b = me.run_calibration(synthetic_pairs(40, 10, seed=6), 5, 100, 9, 10)
        self.assertEqual(a["methods"]["platt"], b["methods"]["platt"])


class BootstrapTest(unittest.TestCase):
    def test_a_resampled_vacancy_that_repeats_stays_a_separate_group(self):
        rows = synthetic_pairs(10, 3, seed=1)
        counted = me.bootstrap_by_vacancy(rows, lambda sample: len({r["vacancy_id"] for r in sample}), 50, 3)
        # Si las repeticiones se fusionaran, habría menos de 10 grupos en la mayoría de extracciones.
        self.assertEqual(counted, (10, 10))

    def test_the_bootstrap_does_not_modify_the_rows_it_receives(self):
        rows = synthetic_pairs(5, 3, seed=1)
        before = [dict(r) for r in rows]
        me.bootstrap_by_vacancy(rows, lambda sample: 0.0, 20, 1)
        self.assertEqual(rows, before)


class RankingTest(unittest.TestCase):
    def test_precision_at_k_counts_grades_two_and_three(self):
        self.assertAlmostEqual(me.precision_at_k([3, 2, 0], 3), 2 / 3)
        self.assertEqual(me.precision_at_k([0, 1, 1], 3), 0.0)

    def test_ndcg_of_a_known_ordering(self):
        self.assertAlmostEqual(me.ndcg_at_k([3, 2, 0, 1], 3), 8.8928 / 9.3928, places=3)
        self.assertEqual(me.ndcg_at_k([3, 2, 1, 0], 4), 1.0)

    def test_ndcg_is_undefined_when_nothing_is_relevant(self):
        self.assertTrue(math.isnan(me.ndcg_at_k([0, 0, 0], 3)))

    def test_ranking_orders_by_score_and_skips_vacancies_shorter_than_k(self):
        rows = [
            {"vacancy_id": "a", "label": 3, "score": 0.9}, {"vacancy_id": "a", "label": 0, "score": 0.1},
            {"vacancy_id": "a", "label": 2, "score": 0.8},
            {"vacancy_id": "b", "label": 3, "score": 0.5},  # una sola fila: no llega a K
        ]
        precision, ndcg, used = me.ranking_metrics(rows, 2)
        self.assertEqual(used, 1)
        self.assertEqual(precision, 1.0)
        self.assertEqual(ndcg, 1.0)

    def test_a_worse_score_gives_a_worse_precision(self):
        rows = synthetic_pairs(80, 10, seed=3)
        good, _, _ = me.ranking_metrics(rows, 5)
        shuffled = [dict(r, score=random.Random(i).random()) for i, r in enumerate(rows)]
        bad, _, _ = me.ranking_metrics(shuffled, 5)
        self.assertGreater(good, bad + 0.15)


class CoverageTest(unittest.TestCase):
    ESCO = [
        ("http://example.test/s/1", "usar Python", "python\nprogramación en python", ""),
        ("http://example.test/s/2", "usar Java", "java", ""),
        ("http://example.test/s/3", "usar JavaScript", "javascript\njs", ""),
        ("http://example.test/s/4", "gestionar proyectos", "gestión de proyectos\nproyectos", ""),
        ("http://example.test/s/5", "gestionar presupuestos", "proyectos", ""),
        ("http://example.test/s/6", "programar en C", "c", ""),
        ("http://example.test/s/7", "programar en C++", "c++", ""),
    ]

    def build_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "esco.csv")
            with open(path, "w", encoding="utf-8", newline="") as handle:
                import csv
                writer = csv.writer(handle)
                writer.writerow(["conceptUri", "preferredLabel", "altLabels", "hiddenLabels"])
                writer.writerows(self.ESCO)
            return me.read_esco(path)

    def test_normalization_keeps_c_and_cpp_and_csharp_apart(self):
        self.assertEqual(me.normalize("C++"), "c plus plus")
        self.assertEqual(me.normalize("C#"), "c sharp")
        self.assertEqual(me.normalize("  C "), "c")
        self.assertNotEqual(me.normalize("C"), me.normalize("C++"))

    def test_normalization_strips_accents_case_and_punctuation(self):
        self.assertEqual(me.normalize("Programación  Python"), "programacion python")
        self.assertEqual(me.normalize("Node.js"), "node js")
        self.assertEqual(me.normalize("INGLÉS (avanzado)"), "ingles avanzado")

    def test_the_index_counts_concepts_and_loads_every_kind_of_label(self):
        index, concepts = self.build_index()
        self.assertEqual(concepts, 7)
        self.assertIn("programacion en python", index)
        self.assertIn("usar python", index)

    def test_a_label_of_one_concept_is_unique(self):
        index, _ = self.build_index()
        self.assertEqual(me.classify(index, "Python"), "UNIQUE")
        self.assertEqual(me.classify(index, "programación en Python"), "UNIQUE")
        self.assertEqual(me.classify(index, "C++"), "UNIQUE")
        self.assertEqual(me.classify(index, "C"), "UNIQUE")

    def test_a_label_shared_by_two_concepts_is_ambiguous_and_never_unique(self):
        index, _ = self.build_index()
        self.assertEqual(me.classify(index, "proyectos"), "AMBIGUOUS")

    def test_an_unknown_text_has_no_link(self):
        index, _ = self.build_index()
        self.assertEqual(me.classify(index, "Kubernetes"), "NONE")
        self.assertEqual(me.classify(index, ""), "NONE")

    def test_coverage_counts_distinct_skills_and_mentions_apart(self):
        index, _ = self.build_index()
        skills = [("Python", "cv1"), ("python", "cv2"), ("PYTHON", "cv3"), ("Kubernetes", "cv1"),
                  ("Kubernetes", "cv2"), ("Go", "cv1"), ("proyectos", "cv2")]
        report = me.run_coverage(index, skills, top=5)
        self.assertEqual(report["distinct"], 4)       # python, kubernetes, go, proyectos
        self.assertEqual(report["mentions"], 7)
        self.assertEqual(report["sources"], 3)
        self.assertEqual(report["by_distinct"]["UNIQUE"], 1)
        self.assertEqual(report["by_mentions"]["UNIQUE"], 3)
        self.assertEqual(report["by_distinct"]["AMBIGUOUS"], 1)
        self.assertEqual(report["by_distinct"]["NONE"], 2)
        self.assertEqual(report["unmatched_top"][0], (2, "Kubernetes"))
        self.assertEqual(report["ambiguous"], ["proyectos"])

    def test_the_interval_of_the_coverage_is_a_wilson_interval(self):
        index, _ = self.build_index()
        report = me.run_coverage(index, [("Python", ""), ("Java", ""), ("Go", ""), ("Rust", "")], top=5)
        low, high = report["ci_unique_distinct"]
        self.assertEqual((low, high), me.wilson_interval(2, 4))

    def test_the_interval_is_over_distinct_skills_and_not_over_mentions(self):
        # Una habilidad muy repetida no debe dar una falsa seguridad: 2 distintas, 5 menciones.
        index, _ = self.build_index()
        skills = [("Python", "")] * 4 + [("Kubernetes", "")]
        report = me.run_coverage(index, skills, top=5)
        self.assertEqual((report["distinct"], report["mentions"]), (2, 5))
        self.assertEqual(report["ci_unique_distinct"], me.wilson_interval(1, 2))
        self.assertEqual(report["ci_none_distinct"], me.wilson_interval(1, 2))


class InputTest(unittest.TestCase):
    def test_pairs_with_a_missing_column_stop_with_a_clear_message(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "p.csv")
            write_csv(path, ["vacancy_id", "candidate_id", "cosine"], [("v", "c", 0.5)])
            with self.assertRaises(SystemExit) as ctx:
                me.read_pairs(path)
            self.assertIn("label", str(ctx.exception))

    def test_a_label_outside_zero_to_three_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "p.csv")
            write_csv(path, ["vacancy_id", "candidate_id", "cosine", "label"], [("v", "c", 0.5, 5)])
            with self.assertRaises(SystemExit):
                me.read_pairs(path)

    def test_a_non_numeric_value_is_rejected_with_its_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "p.csv")
            write_csv(path, ["vacancy_id", "candidate_id", "cosine", "label"], [("v", "c", "abc", 1)])
            with self.assertRaises(SystemExit) as ctx:
                me.read_pairs(path)
            self.assertIn("línea 2", str(ctx.exception))

    def test_the_score_column_defaults_to_the_cosine(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "p.csv")
            write_csv(path, ["vacancy_id", "candidate_id", "cosine", "label"], [("v", "c", 0.5, 3)])
            rows = me.read_pairs(path)
            self.assertEqual(rows[0]["score"], 0.5)
            self.assertEqual(rows[0]["relevant"], 1)

    def test_skills_without_the_column_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "s.csv")
            write_csv(path, ["habilidad"], [("Python",)])
            with self.assertRaises(SystemExit):
                me.read_skills(path)


class CommandLineTest(unittest.TestCase):
    def run_main(self, argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = me.main(argv)
        return code, out.getvalue()

    def test_calibrate_prints_the_figures_and_the_warning_for_few_vacancies(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "p.csv")
            rows = synthetic_pairs(12, 10, seed=1)
            write_csv(path, ["vacancy_id", "candidate_id", "cosine", "label"],
                      [(r["vacancy_id"], r["candidate_id"], r["cosine"], r["label"]) for r in rows])
            code, text = self.run_main(["calibrate", "--pairs", path, "--bootstrap", "30"])
        self.assertEqual(code, 0)
        self.assertIn("120 pares, 12 vacantes", text)
        self.assertIn("AVISO", text)
        self.assertIn("platt", text)
        self.assertIn("NDCG@10", text)

    def test_coverage_prints_the_rates_and_the_candidates_for_local_skills(self):
        import csv
        with tempfile.TemporaryDirectory() as tmp:
            esco = os.path.join(tmp, "esco.csv")
            with open(esco, "w", encoding="utf-8", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(["conceptUri", "preferredLabel", "altLabels", "hiddenLabels"])
                writer.writerow(["http://example.test/s/1", "usar Python", "python", ""])
            skills = os.path.join(tmp, "s.csv")
            write_csv(skills, ["skill", "source_id"], [("Python", "a"), ("Kubernetes", "a"), ("Kubernetes", "b")])
            code, text = self.run_main(["coverage", "--esco", esco, "--skills", skills])
        self.assertEqual(code, 0)
        self.assertIn("1 conceptos", text.replace("(1 conceptos", "1 conceptos"))
        self.assertIn("sin enlace", text)
        self.assertIn("Kubernetes", text)
        self.assertIn("AVISO", text)  # muestra pequeña


if __name__ == "__main__":
    unittest.main()
