package com.usac.jobseeker.job.application.usecase.getapplicationgate;

/**
 * Si una cuenta puede postular a una vacante, según lo que sabe `job`: que exista, que el candidato
 * pueda verla, que acepte postulaciones y que no sea suya. Lo consume `ats` antes de crear la
 * postulación; la capacidad de candidato ya la comprobó `ats`. Es interna: no hay endpoint.
 */
public interface GetApplicationGateUseCase {
  ApplicationGate execute(GetApplicationGateQuery query);
}
