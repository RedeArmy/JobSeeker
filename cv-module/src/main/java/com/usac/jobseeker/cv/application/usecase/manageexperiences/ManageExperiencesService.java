package com.usac.jobseeker.cv.application.usecase.manageexperiences;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La versión del perfil, el
 * evento, la métrica y el log los pone {@link ProfileChange}, igual para las cuatro colecciones;
 * aquí solo queda lo propio de experiencias laborales: construir y validar el elemento y
 * escribirlo.
 */
public class ManageExperiencesService implements ManageExperiencesUseCase {

  private static final String USE_CASE = "manage_experiences";
  private static final String FIELD = "experiences";

  private final ExperienceStorePort items;
  private final ProfileChange change;

  public ManageExperiencesService(ExperienceStorePort items, ProfileChange change) {
    this.items = Objects.requireNonNull(items, "items");
    this.change = Objects.requireNonNull(change, "change");
  }

  @Override
  @Transactional
  public ProfessionalProfileView add(AddExperienceCommand c) {
    Experience item =
        change.validated(
            USE_CASE,
            () ->
                Experience.of(
                    change.newItemId(),
                    c.company(),
                    c.title(),
                    c.startDate(),
                    c.endDate(),
                    c.description()));
    return change.apply(
        USE_CASE,
        "added",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_EXPERIENCE,
        profile -> {
          change.requireRoom(USE_CASE, FIELD, items.count(profile));
          items.insert(profile, item);
        });
  }

  @Override
  @Transactional
  public ProfessionalProfileView update(UpdateExperienceCommand c) {
    Experience item =
        change.validated(
            USE_CASE,
            () ->
                Experience.of(
                    new ProfileItemId(c.itemId()),
                    c.company(),
                    c.title(),
                    c.startDate(),
                    c.endDate(),
                    c.description()));
    return change.apply(
        USE_CASE,
        "updated",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_EXPERIENCE,
        profile -> {
          if (!items.update(profile, item)) {
            throw new NotFoundException();
          }
        });
  }

  @Override
  @Transactional
  public ProfessionalProfileView remove(RemoveExperienceCommand c) {
    return change.apply(
        USE_CASE,
        "removed",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_EXPERIENCE,
        profile -> {
          if (!items.delete(profile, new ProfileItemId(c.itemId()))) {
            throw new NotFoundException();
          }
        });
  }
}
