package com.usac.jobseeker.cv.application.usecase.manageprojects;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.domain.model.ProjectType;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Añadir un elemento a proyectos. La cuenta sale del token, nunca del cuerpo. La versión es la del
 * perfil que el cliente leyó: si otra edición se guardó antes, se rechaza en vez de pisarla.
 */
public record AddProjectCommand(
    AccountId accountId,
    long expectedVersion,
    String name,
    String description,
    ProjectType type,
    String role,
    LocalDate startDate,
    LocalDate endDate,
    String url) {

  public AddProjectCommand {
    Objects.requireNonNull(accountId, "accountId");
    ProfileChange.requireValidVersion(expectedVersion);
  }
}
