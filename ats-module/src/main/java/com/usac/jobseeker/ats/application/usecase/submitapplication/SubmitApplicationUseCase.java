package com.usac.jobseeker.ats.application.usecase.submitapplication;

/**
 * El candidato postula a una vacante (tdd/ats-tracker-modules.md §4.1). Lanza {@code
 * ForbiddenCapabilityException} sin la capacidad de candidato, {@code NotFoundException} si la
 * vacante no existe para él, {@code InvalidStateException} si no acepta postulaciones, {@code
 * ApplicationDeniedException} si es suya o ya postuló, {@code
 * ApplicationRequirementsMissingException} si le falta algo de BR-CAND-01, y {@code
 * IdempotencyKeyReusedException} si la clave ya se usó con otra vacante.
 */
public interface SubmitApplicationUseCase {
  SubmittedApplication execute(SubmitApplicationCommand command);
}
