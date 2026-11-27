package com.usac.jobseeker.cv.application.usecase.checkapplicationreadiness;

/**
 * Qué le falta a una cuenta para postular según BR-CAND-01 (nombre, contacto y CV), y el perfil y
 * el CV con los que postularía. Lo consume `ats` desde su adaptador de salida antes de crear la
 * postulación (`tdd/ats-tracker-modules.md` §5). Que falte algo no es un error: es la respuesta.
 */
public interface CheckApplicationReadinessUseCase {
  ApplicationReadiness execute(CheckApplicationReadinessQuery query);
}
