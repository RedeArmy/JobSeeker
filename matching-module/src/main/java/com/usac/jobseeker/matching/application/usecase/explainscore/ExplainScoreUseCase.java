package com.usac.jobseeker.matching.application.usecase.explainscore;

/**
 * Puerto de entrada: el candidato abre el desglose de su compatibilidad con una vacante que puede
 * ver (BR-VIS-01). Las explicaciones son plantillas deterministas sobre el desglose, sin un modelo
 * generativo (ADR-0017, decisión 7). Consultarlo deja el puntaje vigente guardado con motivo
 * `CANDIDATE_VIEW`, lo que lo mantiene al día cuando algo cambie.
 */
public interface ExplainScoreUseCase {

  /**
   * @throws com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException si la cuenta no es
   *     candidata
   * @throws com.usac.jobseeker.common.domain.error.NotFoundException si la vacante no existe, no la
   *     ven los candidatos o es del propio solicitante
   * @throws com.usac.jobseeker.common.domain.error.InvalidStateException si aún no tiene un perfil
   *     legible
   */
  ExplainScoreResult execute(ExplainScoreQuery query);
}
