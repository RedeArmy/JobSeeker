package com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore;

/**
 * Puerto de entrada: puntúa el par de una cuenta con una vacante y guarda el puntaje vigente. **No
 * llama a Ollama**: lee los vectores y las señales que ya existen (ADR-0017, decisión 1). No decide
 * quién puede verlo: la visibilidad es de quien lo consume (BR-VIS-01 a 04).
 */
public interface CalculateCompatibilityScoreUseCase {

  CalculatedScore execute(CalculateCompatibilityScoreCommand command);
}
