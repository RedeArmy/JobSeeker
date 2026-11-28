package com.usac.jobseeker.job.application.usecase.getpostingsummaries;

import java.util.List;

/**
 * El título y el estado de unas vacantes, **también las eliminadas** (BR-JOB-09): el candidato
 * sigue viendo su postulación a una vacante borrada, marcada (tdd/ats-tracker-modules.md §5 y regla
 * 9). Una vacante que no existe no aparece en la respuesta.
 */
public interface GetPostingSummariesUseCase {
  List<PostingSummary> execute(GetPostingSummariesQuery query);
}
