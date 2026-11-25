package com.usac.jobseeker.job.application.usecase.getownedjobstate;

import java.util.UUID;

/**
 * Lo mínimo que otro módulo necesita saber de una vacante **de la que es dueña** la cuenta: su
 * estado y su etapa de reclutamiento como texto. Ni el contenido, ni los requisitos, ni las fechas
 * de gestión: quien necesite el contenido usa `getjobsignals`.
 */
public record OwnedJobState(UUID jobPostingId, String status, String recruitingStage) {}
