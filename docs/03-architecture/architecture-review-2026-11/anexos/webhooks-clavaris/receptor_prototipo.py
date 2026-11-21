"""Receptor de webhooks de Clavaris — prototipo de verificación para JobSeeker.
Verifica Clavaris-Signature (t=<epoch>,v1=<hmac>[,v1=<hmac>]) = HMAC-SHA256(secreto, "<t>.<cuerpo crudo>"),
con tolerancia de tiempo, deduplicación por Clavaris-Event-Id e inyección de fallos. Solo registra; no ejecuta nada."""
import hmac, hashlib, json, time, os, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 256 * 1024
TOLERANCIA_S = 300
seen, lock = set(), threading.Lock()

def secretos():  # se relee en cada petición: permite registrar o rotar el secreto sin reiniciar
    try: return [s for s in json.load(open("secrets.json")) if s]
    except Exception: return []

def fallos_pendientes():
    try: n = int(open("fail_next.txt").read().strip() or 0)
    except Exception: return 0
    return n

def consumir_fallo():
    n = fallos_pendientes()
    if n > 0: open("fail_next.txt", "w").write(str(n - 1))
    return n > 0

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        largo = int(self.headers.get("Content-Length", 0))
        if largo > MAX_BODY: self.send_response(413); self.end_headers(); return
        raw = self.rfile.read(largo); cuerpo = raw.decode("utf-8", "replace")
        hdr = self.headers.get("Clavaris-Signature", ""); partes = dict()
        v1 = [p[3:] for p in hdr.split(",") if p.startswith("v1=")]
        t = next((p[2:] for p in hdr.split(",") if p.startswith("t=")), "")
        firma_ok = False
        if t.isdigit():
            for s in secretos():
                esperado = hmac.new(s.encode(), f"{t}.{cuerpo}".encode(), hashlib.sha256).hexdigest()
                if any(hmac.compare_digest(esperado, x) for x in v1): firma_ok = True
        tiempo_ok = t.isdigit() and abs(time.time() - int(t)) <= TOLERANCIA_S
        eid = self.headers.get("Clavaris-Event-Id", "")
        with lock: duplicado = eid in seen; seen.add(eid)
        inyectar = firma_ok and consumir_fallo()
        decision = 401 if not (firma_ok and tiempo_ok) else 500 if inyectar else 200
        try: payload = json.loads(cuerpo)
        except Exception: payload = None
        open("hooks.jsonl", "a").write(json.dumps({
            "ts": time.strftime("%H:%M:%S"), "evento": self.headers.get("Clavaris-Event-Type"), "event_id": eid,
            "delivery_id": self.headers.get("Clavaris-Delivery-Id"), "trace": self.headers.get("Clavaris-Trace-Id"),
            "n_firmas_v1": len(v1), "firma_valida": firma_ok, "tiempo_ok": tiempo_ok, "duplicado": duplicado,
            "decision_http": decision, "cabeceras": sorted(k for k in self.headers.keys()), "payload": payload}, ensure_ascii=False) + "\n")
        self.send_response(decision); self.end_headers(); self.wfile.write(b"ok")
    def log_message(self, *a): pass

ThreadingHTTPServer(("127.0.0.1", 19099), H).serve_forever()
