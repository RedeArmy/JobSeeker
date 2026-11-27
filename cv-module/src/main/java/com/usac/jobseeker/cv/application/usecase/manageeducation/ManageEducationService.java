package com.usac.jobseeker.cv.application.usecase.manageeducation;

import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.support.ProfileItemChanges;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.Education;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El flujo de la colección
 * (validar, máximo, 404, versión, evento y métrica) es el de {@link ProfileItemChanges}, igual para
 * las cuatro (TD-013); aquí solo queda cómo se construye el elemento a partir del comando.
 */
public class ManageEducationService implements ManageEducationUseCase {

  private final ProfileItemChanges<Education> changes;

  public ManageEducationService(EducationStorePort items, ProfileChange change) {
    this.changes =
        new ProfileItemChanges<>(
            "manage_education", "education", ProfileUpdated.CHANGE_EDUCATION, items, change);
  }

  @Override
  @Transactional
  public ProfessionalProfileView add(AddEducationCommand c) {
    return changes.add(
        c.accountId(),
        c.expectedVersion(),
        id -> Education.of(id, c.institution(), c.degree(), c.level(), c.completionDate()));
  }

  @Override
  @Transactional
  public ProfessionalProfileView update(UpdateEducationCommand c) {
    return changes.update(
        c.accountId(),
        c.expectedVersion(),
        c.itemId(),
        id -> Education.of(id, c.institution(), c.degree(), c.level(), c.completionDate()));
  }

  @Override
  @Transactional
  public ProfessionalProfileView remove(RemoveEducationCommand c) {
    return changes.remove(c.accountId(), c.expectedVersion(), c.itemId());
  }
}
