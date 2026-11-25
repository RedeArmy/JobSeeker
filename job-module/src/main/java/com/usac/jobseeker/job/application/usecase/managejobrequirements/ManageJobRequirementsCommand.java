package com.usac.jobseeker.job.application.usecase.managejobrequirements;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.job.application.support.JobChange;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirementDraft;
import java.util.ArrayList;
import java.util.Collections;
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
    // `List.copyOf` rechaza los nulos y un `[null]` del cliente llegaría como un 500; la validación
    // del
    // conjunto lo convierte en un 400 que dice qué posición falta.
    requirements =
        requirements == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(requirements));
    JobChange.requireValidVersion(expectedVersion);
  }
}
