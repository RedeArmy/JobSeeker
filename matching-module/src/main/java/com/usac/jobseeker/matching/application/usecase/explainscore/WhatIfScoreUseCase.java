package com.usac.jobseeker.matching.application.usecase.explainscore;

/**
 * Puerto de entrada: el candidato prueba cómo cambiaría su puntaje con una habilidad o un idioma
 * que declara tener. Es aritmética pura sobre el mismo agregador (ADR-0017, decisión 7), no guarda
 * nada y no cambia su perfil.
 */
public interface WhatIfScoreUseCase {

  WhatIfScoreResult execute(WhatIfScoreQuery query);
}
