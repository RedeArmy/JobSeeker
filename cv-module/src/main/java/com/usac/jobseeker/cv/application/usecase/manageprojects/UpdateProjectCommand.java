package com.usac.jobseeker.cv.application.usecase.manageprojects;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.domain.model.ProjectType;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Reemplazar un elemento de proyectos. Solo se encuentra si es del perfil de quien llama. */
public record UpdateProjectCommand(
    AccountId accountId,
    long expectedVersion,
    UUID itemId,
    String name,
    String description,
    ProjectType type,
    String role,
    LocalDate startDate,
    LocalDate endDate,
    String url) {

  public UpdateProjectCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(itemId, "itemId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
