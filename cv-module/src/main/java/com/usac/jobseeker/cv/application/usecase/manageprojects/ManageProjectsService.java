package com.usac.jobseeker.cv.application.usecase.manageprojects;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.Project;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La versión del perfil, el
 * evento, la métrica y el log los pone {@link ProfileChange}, igual para las cuatro colecciones;
 * aquí solo queda lo propio de proyectos: construir y validar el elemento y escribirlo.
 */
public class ManageProjectsService implements ManageProjectsUseCase {

  private static final String USE_CASE = "manage_projects";
  private static final String FIELD = "projects";

  private final ProjectStorePort items;
  private final ProfileChange change;

  public ManageProjectsService(ProjectStorePort items, ProfileChange change) {
    this.items = Objects.requireNonNull(items, "items");
    this.change = Objects.requireNonNull(change, "change");
  }

  @Override
  @Transactional
  public ProfessionalProfileView add(AddProjectCommand c) {
    Project item =
        change.validated(
            USE_CASE,
            () ->
                Project.of(
                    change.newItemId(),
                    c.name(),
                    c.description(),
                    c.type(),
                    c.role(),
                    c.startDate(),
                    c.endDate(),
                    c.url()));
    return change.apply(
        USE_CASE,
        "added",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_PROJECT,
        profile -> {
          change.requireRoom(USE_CASE, FIELD, items.count(profile));
          items.insert(profile, item);
        });
  }

  @Override
  @Transactional
  public ProfessionalProfileView update(UpdateProjectCommand c) {
    Project item =
        change.validated(
            USE_CASE,
            () ->
                Project.of(
                    new ProfileItemId(c.itemId()),
                    c.name(),
                    c.description(),
                    c.type(),
                    c.role(),
                    c.startDate(),
                    c.endDate(),
                    c.url()));
    return change.apply(
        USE_CASE,
        "updated",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_PROJECT,
        profile -> {
          if (!items.update(profile, item)) {
            throw new NotFoundException();
          }
        });
  }

  @Override
  @Transactional
  public ProfessionalProfileView remove(RemoveProjectCommand c) {
    return change.apply(
        USE_CASE,
        "removed",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_PROJECT,
        profile -> {
          if (!items.delete(profile, new ProfileItemId(c.itemId()))) {
            throw new NotFoundException();
          }
        });
  }
}
