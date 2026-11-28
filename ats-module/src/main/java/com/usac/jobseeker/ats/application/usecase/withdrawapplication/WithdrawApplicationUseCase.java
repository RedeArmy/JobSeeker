package com.usac.jobseeker.ats.application.usecase.withdrawapplication;

import com.usac.jobseeker.ats.application.usecase.listcandidateapplications.CandidateApplication;

/**
 * El candidato retira una postulación propia y en proceso (REQ-C-03); podrá volver a postular a la
 * misma vacante (D3). Lanza {@code ForbiddenCapabilityException} sin la capacidad de candidato,
 * {@code NotFoundException} si la postulación no existe o es de otro, {@code
 * InvalidTransitionException} si ya terminó y {@code StaleStateException} si otro cambio llegó
 * antes.
 */
public interface WithdrawApplicationUseCase {
  CandidateApplication execute(WithdrawApplicationCommand command);
}
