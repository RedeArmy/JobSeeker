package com.usac.jobseeker.cv.application.usecase.manageprojects;

import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.support.ProfileItemChanges;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.Project;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El flujo de la colección
 * (validar, máximo, 404, versión, evento y métrica) es el de {@link ProfileItemChanges}, igual para
 * las cuatro (TD-013); aquí solo queda cómo se construye el elemento a partir del comando.
 */
public class ManageProjectsService implements ManageProjectsUseCase {

  private final ProfileItemChanges<Project> changes;

  public ManageProjectsService(ProjectStorePort items, ProfileChange change) {
    this.changes =
        new ProfileItemChanges<>(
            "manage_projects", "projects", ProfileUpdated.CHANGE_PROJECT, items, change);
  }

  @Override
  @Transactional
  public ProfessionalProfileView add(AddProjectCommand c) {
    return changes.add(
        c.accountId(),
        c.expectedVersion(),
        id ->
            Project.of(
                id,
                c.name(),
                c.description(),
                c.type(),
                c.role(),
                c.startDate(),
                c.endDate(),
                c.url()));
  }

  @Override
  @Transactional
  public ProfessionalProfileView update(UpdateProjectCommand c) {
    return changes.update(
        c.accountId(),
        c.expectedVersion(),
        c.itemId(),
        id ->
            Project.of(
                id,
                c.name(),
                c.description(),
                c.type(),
                c.role(),
                c.startDate(),
                c.endDate(),
                c.url()));
  }

  @Override
  @Transactional
  public ProfessionalProfileView remove(RemoveProjectCommand c) {
    return changes.remove(c.accountId(), c.expectedVersion(), c.itemId());
  }
}
