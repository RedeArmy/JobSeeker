package com.usac.jobseeker.ats.application.usecase.listcandidateapplications;

import java.util.List;

/**
 * Las postulaciones de un candidato con su historial, de la más reciente a la más antigua. Interna:
 * la consume `tracker`, que no tiene tablas propias y lee siempre por aquí (ADR-0027, TDD §2).
 */
public interface ListCandidateApplicationsUseCase {
  List<CandidateApplication> execute(ListCandidateApplicationsQuery query);
}
