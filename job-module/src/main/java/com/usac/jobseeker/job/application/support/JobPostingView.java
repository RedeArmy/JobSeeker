package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.job.domain.model.JobPosting;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import java.util.List;
import java.util.Objects;

/**
 * La vacante con sus requisitos, tal como la ve quien la pide. `ownedByViewer` decide qué se
 * expone: el dueño ve todo (versión, fechas, etapa); un candidato solo lo que le sirve para decidir
 * si postula, y nunca quién la escribió (BR-VIS-03).
 */
public record JobPostingView(
    JobPosting posting, List<JobRequirement> requirements, boolean ownedByViewer) {

  public JobPostingView {
    Objects.requireNonNull(posting, "posting");
    requirements = List.copyOf(requirements);
  }
}
