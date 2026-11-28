package com.usac.jobseeker.job.application.usecase.getpostingsummaries;

import java.util.UUID;

/**
 * Lo mínimo para mostrar una vacante en el tracker: su título y su estado como texto (`DELETED`
 * incluido, BR-JOB-09). Ni el reclutador, ni la descripción, ni las fechas de gestión.
 */
public record PostingSummary(UUID jobPostingId, String title, String status) {}
