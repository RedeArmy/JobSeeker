package com.usac.jobseeker.cv.application.usecase.managecertifications;

import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). La versión del perfil, el
 * evento, la métrica y el log los pone {@link ProfileChange}, igual para las cuatro colecciones;
 * aquí solo queda lo propio de certificaciones: construir y validar el elemento y escribirlo.
 */
public class ManageCertificationsService implements ManageCertificationsUseCase {

  private static final String USE_CASE = "manage_certifications";
  private static final String FIELD = "certifications";

  private final CertificationStorePort items;
  private final ProfileChange change;

  public ManageCertificationsService(CertificationStorePort items, ProfileChange change) {
    this.items = Objects.requireNonNull(items, "items");
    this.change = Objects.requireNonNull(change, "change");
  }

  @Override
  @Transactional
  public ProfessionalProfileView add(AddCertificationCommand c) {
    Certification item =
        change.validated(
            USE_CASE,
            () ->
                Certification.of(
                    change.newItemId(),
                    c.name(),
                    c.issuingOrganization(),
                    c.issueDate(),
                    c.expirationDate(),
                    c.credentialUrl()));
    return change.apply(
        USE_CASE,
        "added",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_CERTIFICATION,
        profile -> {
          change.requireRoom(USE_CASE, FIELD, items.count(profile));
          items.insert(profile, item);
        });
  }

  @Override
  @Transactional
  public ProfessionalProfileView update(UpdateCertificationCommand c) {
    Certification item =
        change.validated(
            USE_CASE,
            () ->
                Certification.of(
                    new ProfileItemId(c.itemId()),
                    c.name(),
                    c.issuingOrganization(),
                    c.issueDate(),
                    c.expirationDate(),
                    c.credentialUrl()));
    return change.apply(
        USE_CASE,
        "updated",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_CERTIFICATION,
        profile -> {
          if (!items.update(profile, item)) {
            throw new NotFoundException();
          }
        });
  }

  @Override
  @Transactional
  public ProfessionalProfileView remove(RemoveCertificationCommand c) {
    return change.apply(
        USE_CASE,
        "removed",
        c.accountId(),
        c.expectedVersion(),
        ProfileUpdated.CHANGE_CERTIFICATION,
        profile -> {
          if (!items.delete(profile, new ProfileItemId(c.itemId()))) {
            throw new NotFoundException();
          }
        });
  }
}
