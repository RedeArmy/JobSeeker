"""Pruebas de `embedding_latency.py` contra un Ollama simulado (no mide nada real).

    python3 -I -m unittest discover -s scripts/eval/tests -t scripts/eval/tests
"""

import contextlib
import http.server
import importlib.util
import io
import json
import os
import threading
import time
import unittest
from unittest import mock

_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("embedding_latency", os.path.join(_HERE, "..", "embedding_latency.py"))
el = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(el)


class FakeOllama(http.server.BaseHTTPRequestHandler):
    delay = 0.0
    bodies = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeOllama.bodies.append(body)
        time.sleep(FakeOllama.delay)
        payload = json.dumps({"embeddings": [[0.1] * 8]}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


class EmbeddingLatencyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeOllama)
        cls.port = cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        FakeOllama.delay = 0.0
        FakeOllama.bodies = []

    def run_main(self, *extra):
        argv = ["x", "--port", str(self.port), "--repeats", "2", *extra]
        out, err = io.StringIO(), io.StringIO()
        with mock.patch("sys.argv", argv), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = el.main()
        return code, out.getvalue(), err.getvalue()

    def test_percentile_is_the_nearest_rank(self):
        self.assertEqual(el.percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 0.95), 10)
        self.assertEqual(el.percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 0.5), 5)
        self.assertEqual(el.percentile([7], 0.95), 7)

    def test_text_has_exactly_the_requested_size(self):
        for chars in (100, 400, 1600):
            self.assertEqual(len(el.text_of(chars)), chars)

    def test_without_avx2_it_refuses_unless_told_otherwise(self):
        with mock.patch.object(el, "cpu_flags", return_value={"sse4_2"}):
            code, _, err = self.run_main()
        self.assertEqual(code, 2)
        self.assertIn("AVX2", err)
        self.assertEqual(FakeOllama.bodies, [])

    def test_with_avx2_a_fast_server_fits_the_budget_and_sends_the_agreed_request(self):
        with mock.patch.object(el, "cpu_flags", return_value={"avx2", "avx"}):
            code, out, _ = self.run_main("--num-thread", "2")
        self.assertEqual(code, 0)
        self.assertIn("CABE", out)
        body = FakeOllama.bodies[0]
        self.assertIs(body["truncate"], False)
        self.assertEqual(body["options"], {"num_ctx": 1024, "num_thread": 2})

    def test_a_slow_server_does_not_fit_the_budget(self):
        FakeOllama.delay = 0.05
        with mock.patch.object(el, "cpu_flags", return_value={"avx2"}):
            code, out, _ = self.run_main("--budget", "20", "--window", "30")
        self.assertEqual(code, 1)
        self.assertIn("NO CABE", out)

    def test_an_unreachable_ollama_is_not_a_measurement(self):
        with mock.patch.object(el, "cpu_flags", return_value={"avx2"}), mock.patch("sys.argv", ["x", "--port", "1", "--repeats", "1"]):
            err = io.StringIO()
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
                code = el.main()
        self.assertEqual(code, 2)
        self.assertIn("no respondió", err.getvalue())


if __name__ == "__main__":
    unittest.main()
