package com.usac.jobseeker.job.application.usecase.getjobsignals;

import java.util.Map;
import java.util.UUID;

/**
 * La variante por lotes de {@link GetJobSignalsUseCase}, con las mismas reglas: una vacante que no
 * existe, es un borrador o está eliminada no figura en el resultado. El costo no crece con el
 * número de vacantes: dos consultas en total.
 */
public interface GetJobSignalsBatchUseCase {

  Map<UUID, JobSnapshot> executeAll(GetJobSignalsBatchQuery query);
}
