package com.usac.jobseeker.cv.application.usecase.manageexperiences;

import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.support.ProfileItemChanges;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.Experience;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El flujo de la colección
 * (validar, máximo, 404, versión, evento y métrica) es el de {@link ProfileItemChanges}, igual para
 * las cuatro (TD-013); aquí solo queda cómo se construye el elemento a partir del comando.
 */
public class ManageExperiencesService implements ManageExperiencesUseCase {

  private final ProfileItemChanges<Experience> changes;

  public ManageExperiencesService(ExperienceStorePort items, ProfileChange change) {
    this.changes =
        new ProfileItemChanges<>(
            "manage_experiences", "experiences", ProfileUpdated.CHANGE_EXPERIENCE, items, change);
  }

  @Override
  @Transactional
  public ProfessionalProfileView add(AddExperienceCommand c) {
    return changes.add(
        c.accountId(),
        c.expectedVersion(),
        id ->
            Experience.of(id, c.company(), c.title(), c.startDate(), c.endDate(), c.description()));
  }

  @Override
  @Transactional
  public ProfessionalProfileView update(UpdateExperienceCommand c) {
    return changes.update(
        c.accountId(),
        c.expectedVersion(),
        c.itemId(),
        id ->
            Experience.of(id, c.company(), c.title(), c.startDate(), c.endDate(), c.description()));
  }

  @Override
  @Transactional
  public ProfessionalProfileView remove(RemoveExperienceCommand c) {
    return changes.remove(c.accountId(), c.expectedVersion(), c.itemId());
  }
}
