package com.usac.jobseeker.matching.application.usecase.getapplicantscores;

import java.util.List;

/**
 * Por lotes, el puntaje vigente de cada postulante pedido con su desglose y el que tenía al
 * postular (tdd/ats-tracker-modules.md §5). Una entrada por perfil pedido, en el mismo orden;
 * `PENDING` si aún no hay puntaje.
 */
public interface GetApplicantScoresUseCase {
  List<ApplicantScore> execute(GetApplicantScoresQuery query);
}
