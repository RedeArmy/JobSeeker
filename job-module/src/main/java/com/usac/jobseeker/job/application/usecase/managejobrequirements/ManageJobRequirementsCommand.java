package com.usac.jobseeker.job.application.usecase.managejobrequirements;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirementDraft;
import java.util.List;
import java.util.Objects;

/**
 * Reemplazar el conjunto completo de requisitos de una vacante propia. Reemplazar y no añadir uno a
 * uno: el conjunto se valida como un todo y el cliente envía lo que quiere que quede.
 */
public record ManageJobRequirementsCommand(
    AccountId owner,
    JobPostingId id,
    long expectedVersion,
    List<JobRequirementDraft> requirements) {

  public ManageJobRequirementsCommand {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(id, "id");
    requirements = requirements == null ? List.of() : List.copyOf(requirements);
    JobChange.requireValidVersion(expectedVersion);
  }
}
