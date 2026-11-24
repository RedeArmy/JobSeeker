package com.usac.jobseeker.matching.application.usecase.embedentity;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import java.util.Optional;

/**
 * Puerto de salida: lo que `matching` sabe hoy de un candidato (capa anticorrupción hacia `cv`).
 * Vacío si la cuenta no tiene perfil legible: sin capacidad de candidato, sin perfil o anonimizado.
 * Quien lo llame no distingue por qué, y no debe: en los tres casos no hay nada que embeber.
 */
public interface CandidateSignalsReader {

  Optional<CandidateSignals> read(AccountId accountId);
}
