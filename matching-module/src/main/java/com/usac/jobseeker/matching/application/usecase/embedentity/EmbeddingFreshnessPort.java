package com.usac.jobseeker.matching.application.usecase.embedentity;

import java.time.Duration;

/**
 * Puerto de salida de la medición de frescura (ADR-0017, decisión 10 y control C4): cuánto pasó
 * desde que había una edición sin atender hasta que su efecto quedó aplicado. Es la métrica con la
 * que se demuestra y se vigila el objetivo de p95 ≤ 120 s; no lleva ningún dato del perfil.
 */
public interface EmbeddingFreshnessPort {

  void observe(Duration sinceEdit);
}
