# Anexo — Prototipo de receptor de webhooks de Clavaris

Resultado de la prueba del 16-nov-2026 (`../../06-spike-contrato-clavaris.md`, addendum). **Es un prototipo de verificación, no el receptor de producción** de JobSeeker.

- `receptor_prototipo.py`: verifica `Clavaris-Signature` (HMAC-SHA256 sobre `"<t>.<cuerpo crudo>"`, una o varias firmas `v1`), con tolerancia de tiempo, límite de tamaño y inyección de fallos para probar reintentos. **Trampa conocida:** marca el `Event-Id` como visto al *recibir*, no al *procesar*; en producción la deduplicación debe registrarse junto con el efecto (si no, un reintento tras un fallo se descarta como duplicado).
- `eventos_recibidos_sin_pii.json`: 14 entregas reales recibidas a través de un túnel HTTPS (cuentas de prueba, correos omitidos), con resultado de verificación y cabeceras.

Para repetir la prueba hace falta una URL HTTPS pública (Clavaris rechaza `localhost` y rangos privados). Se usó un *Cloudflare Quick Tunnel* temporal; no dejar el receptor expuesto fuera de la prueba.

## Entrega real del 20-nov-2026

- `entrega_real_20nov_sin_pii.json`: 19 entregas reales de Clavaris `95a88f67` recibidas por un túnel temporal, con tiempo relativo a una rotación de secreto, número de firmas `v1` y qué secreto valida cada una (`current`/`previous`). Cuerpos omitidos; solo nombres de campo. Resultado y conclusiones: addendum 14 de `../../06-spike-contrato-clavaris.md`.
