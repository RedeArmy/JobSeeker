"""Pruebas de `retrieval-recall` (control C1) y `emit-config` (calibración) de `matching_eval.py`.

Los datos son SINTÉTICOS y con verdad conocida: demuestran que las guardas se comportan, no dicen
nada del sistema real. Se ejecutan con el intérprete aislado:

    python3 -I -m unittest discover -s scripts/eval/tests -t scripts/eval/tests
"""

import contextlib
import csv
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


def pair(vacancy, candidate, cosine, label):
    return {"vacancy_id": vacancy, "candidate_id": candidate, "cosine": cosine, "label": label, "relevant": 1 if label >= 2 else 0, "score": cosine}


def synthetic(vacancies, per_vacancy, a, b, seed, anti=False):
    """Etiquetas de Bernoulli(sigmoide(a*cos + b)) con coseno uniforme en [0, 1]."""
    rng = random.Random(seed)
    rows = []
    for v in range(vacancies):
        for c in range(per_vacancy):
            cosine = rng.random()
            p = me.sigmoid((-a if anti else a) * cosine + b)
            rows.append(pair("v%d" % v, "c%d" % c, cosine, 3 if rng.random() < p else 0))
    return rows


def write_pairs(rows):
    handle = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, newline="", encoding="utf-8")
    writer = csv.writer(handle)
    writer.writerow(["vacancy_id", "candidate_id", "cosine", "label"])
    for r in rows:
        writer.writerow([r["vacancy_id"], r["candidate_id"], r["cosine"], r["label"]])
    handle.close()
    return handle.name


def run_main(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = me.main(list(argv))
    return code, out.getvalue(), err.getvalue()


class RetrievalRecallTest(unittest.TestCase):
    def test_recovering_every_relevant_is_a_recall_of_one(self):
        # Los relevantes tienen el mayor coseno: caben todos en el tope.
        rows = [pair("v", "r%d" % i, 0.9 - i * 0.01, 3) for i in range(3)] + [pair("v", "n%d" % i, 0.1, 0) for i in range(10)]
        value, vacancies, trivial = me.retrieval_recall(rows, 5)
        self.assertEqual(value, 1.0)
        self.assertEqual((vacancies, trivial), (1, 0))

    def test_relevant_pairs_below_the_cap_are_lost(self):
        rows = [pair("v", "n%d" % i, 0.9 - i * 0.01, 0) for i in range(10)] + [pair("v", "r", 0.05, 3)]
        value, _, _ = me.retrieval_recall(rows, 5)
        self.assertEqual(value, 0.0)

    def test_the_recall_is_the_mean_over_vacancies(self):
        good = [pair("a", "r", 0.9, 3)] + [pair("a", "n%d" % i, 0.1, 0) for i in range(9)]
        bad = [pair("b", "r", 0.0, 3)] + [pair("b", "n%d" % i, 0.5, 0) for i in range(9)]
        value, vacancies, _ = me.retrieval_recall(good + bad, 3)
        self.assertEqual(value, 0.5)
        self.assertEqual(vacancies, 2)

    def test_a_vacancy_without_relevant_pairs_does_not_count(self):
        rows = [pair("a", "n%d" % i, 0.5, 0) for i in range(5)] + [pair("b", "r", 0.9, 3)]
        self.assertEqual(me.retrieval_recall(rows, 2)[1], 1)

    def test_a_set_no_larger_than_the_cap_is_reported_as_trivial(self):
        rows = [pair("a", "r", 0.1, 3), pair("a", "n", 0.9, 0)]
        value, _, trivial = me.retrieval_recall(rows, 5)
        self.assertEqual(value, 1.0)
        self.assertEqual(trivial, 1)

    def test_the_verdict_and_the_warnings(self):
        trivial = me.run_retrieval_recall([pair("a", "r", 0.1, 3), pair("a", "n", 0.9, 0)], 5, 0.95, 50, 1)
        self.assertTrue(any("no informa" in w for w in trivial["warnings"]))
        empty = me.run_retrieval_recall([pair("a", "n", 0.9, 0)], 5, 0.95, 50, 1)
        self.assertNotIn("passes", empty)
        self.assertTrue(empty["warnings"])
        rows = synthetic(40, 30, 12, -6, 3)
        result = me.run_retrieval_recall(rows, 10, 0.95, 100, 1)
        self.assertEqual(result["passes"], result["recall"] >= 0.95)
        self.assertLessEqual(result["ci"][0], result["ci"][1])

    def test_the_command_exits_with_the_verdict(self):
        good = []
        for v in range(5):
            good += [pair("v%d" % v, "r", 0.9, 3)] + [pair("v%d" % v, "n%d" % i, 0.1, 0) for i in range(20)]
        path = write_pairs(good)
        self.addCleanup(os.unlink, path)
        code, out, _ = run_main("retrieval-recall", "--pairs", path, "--cap", "5", "--bootstrap", "50")
        self.assertEqual(code, 0)
        self.assertIn("CUMPLE", out)
        bad = []
        for v in range(5):
            bad += [pair("v%d" % v, "r", 0.0, 3)] + [pair("v%d" % v, "n%d" % i, 0.5, 0) for i in range(20)]
        path2 = write_pairs(bad)
        self.addCleanup(os.unlink, path2)
        code, out, _ = run_main("retrieval-recall", "--pairs", path2, "--cap", "5", "--bootstrap", "50")
        self.assertEqual(code, 1)
        self.assertIn("NO CUMPLE", out)


class EmitConfigTest(unittest.TestCase):
    def emit(self, rows, **kwargs):
        defaults = dict(model="modelo", folds=5, seed=7, min_pairs=300, min_vacancies=30, max_ece=0.10)
        defaults.update(kwargs)
        return me.emit_config(rows, **defaults)

    def test_a_good_calibration_emits_the_spring_properties(self):
        lines = self.emit(synthetic(60, 10, 8, -4, 5))
        props = dict(l.split("=", 1) for l in lines if not l.startswith("#"))
        self.assertIn("jobseeker.matching.scoring.calibration.a", props)
        self.assertGreater(float(props["jobseeker.matching.scoring.calibration.a"]), 0)
        self.assertTrue(math.isfinite(float(props["jobseeker.matching.scoring.calibration.b"])))
        self.assertIn("modelo", lines[0])

    def test_the_fitted_parameters_recover_the_truth_roughly(self):
        lines = self.emit(synthetic(200, 20, 8, -4, 9))
        props = dict(l.split("=", 1) for l in lines if not l.startswith("#"))
        self.assertAlmostEqual(float(props["jobseeker.matching.scoring.calibration.a"]), 8, delta=2.5)
        self.assertAlmostEqual(float(props["jobseeker.matching.scoring.calibration.b"]), -4, delta=1.5)

    def test_too_little_data_is_refused(self):
        with self.assertRaisesRegex(me.Refusal, "al menos 300"):
            self.emit(synthetic(10, 10, 8, -4, 5))
        with self.assertRaisesRegex(me.Refusal, "vacantes"):
            self.emit(synthetic(20, 20, 8, -4, 5))

    def test_a_single_class_is_refused(self):
        rows = [pair("v%d" % (i % 40), "c%d" % i, i / 400, 0) for i in range(400)]
        with self.assertRaisesRegex(me.Refusal, "dos clases"):
            self.emit(rows)

    # Una pendiente negativa haría el puntaje decreciente con la afinidad: no se publica nunca.
    def test_a_non_monotone_fit_is_refused(self):
        with self.assertRaisesRegex(me.Refusal, "positiva"):
            self.emit(synthetic(60, 10, 8, -4, 5, anti=True))

    def test_a_calibration_no_better_than_the_base_rate_is_refused(self):
        rng = random.Random(11)
        rows = [pair("v%d" % (i % 40), "c%d" % i, rng.random(), 3 if rng.random() < 0.3 else 0) for i in range(600)]
        with self.assertRaises(me.Refusal):
            self.emit(rows)

    def test_an_error_above_the_maximum_is_refused(self):
        with self.assertRaisesRegex(me.Refusal, "error de calibración"):
            self.emit(synthetic(60, 10, 8, -4, 5), max_ece=0.0)

    def test_the_command_prints_properties_or_refuses_with_a_code(self):
        path = write_pairs(synthetic(60, 10, 8, -4, 5))
        self.addCleanup(os.unlink, path)
        code, out, _ = run_main("emit-config", "--pairs", path, "--model", "qwen3-embedding:0.6b")
        self.assertEqual(code, 0)
        self.assertIn("jobseeker.matching.scoring.calibration.a=", out)
        small = write_pairs(synthetic(5, 5, 8, -4, 5))
        self.addCleanup(os.unlink, small)
        code, out, err = run_main("emit-config", "--pairs", small, "--model", "m")
        self.assertEqual(code, 1)
        self.assertEqual(out, "")
        self.assertIn("NO SE EMITE", err)


if __name__ == "__main__":
    unittest.main()
