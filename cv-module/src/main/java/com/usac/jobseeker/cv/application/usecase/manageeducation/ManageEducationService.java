package com.usac.jobseeker.cv.application.usecase.manageeducation;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La versión del perfil, el
 * evento, la métrica y el log los pone {@link ProfileChange}, igual para las cuatro colecciones;
 * aquí solo queda lo propio de formaciones académicas: construir y validar el elemento y
 * escribirlo.
 */
public class ManageEducationService implements ManageEducationUseCase {

  private static final String USE_CASE = "manage_education";
  private static final String FIELD = "education";

  private final EducationStorePort items;
  private final ProfileChange change;

  public ManageEducationService(EducationStorePort items, ProfileChange change) {
    this.items = Objects.requireNonNull(items, "items");
    this.change = Objects.requireNonNull(change, "change");
  }

  @Override
  @Transactional
  public ProfessionalProfileView add(AddEducationCommand c) {
    Education item =
        change.validated(
            USE_CASE,
            () ->
                Education.of(
                    change.newItemId(),
                    c.institution(),
                    c.degree(),
                    c.level(),
                    c.completionDate()));
    return change.apply(
        USE_CASE,
        "added",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_EDUCATION,
        profile -> {
          change.requireRoom(USE_CASE, FIELD, items.count(profile));
          items.insert(profile, item);
        });
  }

  @Override
  @Transactional
  public ProfessionalProfileView update(UpdateEducationCommand c) {
    Education item =
        change.validated(
            USE_CASE,
            () ->
                Education.of(
                    new ProfileItemId(c.itemId()),
                    c.institution(),
                    c.degree(),
                    c.level(),
                    c.completionDate()));
    return change.apply(
        USE_CASE,
        "updated",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_EDUCATION,
        profile -> {
          if (!items.update(profile, item)) {
            throw new NotFoundException();
          }
        });
  }

  @Override
  @Transactional
  public ProfessionalProfileView remove(RemoveEducationCommand c) {
    return change.apply(
        USE_CASE,
        "removed",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_EDUCATION,
        profile -> {
          if (!items.delete(profile, new ProfileItemId(c.itemId()))) {
            throw new NotFoundException();
          }
        });
  }
}
