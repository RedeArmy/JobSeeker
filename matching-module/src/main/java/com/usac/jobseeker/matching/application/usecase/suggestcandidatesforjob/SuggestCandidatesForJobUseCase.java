package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

/**
 * Puerto de entrada: los candidatos sugeridos de una vacante propia (BR-MATCH-06). Hoy es la
 * **etapa de recuperación** del pipeline (ADR-0017): el acceso y la cuota están completos; el
 * puntaje con su desglose y la prioridad por disponibilidad llegan con las etapas siguientes.
 */
public interface SuggestCandidatesForJobUseCase {

  /**
   * @throws com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException si la cuenta no es
   *     reclutadora
   * @throws com.usac.jobseeker.common.domain.error.SuggestionsDeniedException si no está
   *     verificada, está suspendida o agotó la cuota de la ventana
   * @throws com.usac.jobseeker.common.domain.error.NotFoundException si la vacante no es suya
   * @throws com.usac.jobseeker.common.domain.error.InvalidStateException si la vacante no está
   *     publicada
   */
  SuggestionsResult execute(SuggestCandidatesForJobQuery query);
}
