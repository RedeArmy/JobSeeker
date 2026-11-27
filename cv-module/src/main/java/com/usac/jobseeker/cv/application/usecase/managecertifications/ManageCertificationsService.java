package com.usac.jobseeker.cv.application.usecase.managecertifications;

import com.usac.jobseeker.cv.application.support.ProfileChange;
import com.usac.jobseeker.cv.application.support.ProfileItemChanges;
import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfessionalProfileView;
import com.usac.jobseeker.cv.domain.event.ProfileUpdated;
import com.usac.jobseeker.cv.domain.model.Certification;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config` (ADR-0015, ARCH-02). El flujo de la colección
 * (validar, máximo, 404, versión, evento y métrica) es el de {@link ProfileItemChanges}, igual para
 * las cuatro (TD-013); aquí solo queda cómo se construye el elemento a partir del comando.
 */
public class ManageCertificationsService implements ManageCertificationsUseCase {

  private final ProfileItemChanges<Certification> changes;

  public ManageCertificationsService(CertificationStorePort items, ProfileChange change) {
    this.changes =
        new ProfileItemChanges<>(
            "manage_certifications",
            "certifications",
            ProfileUpdated.CHANGE_CERTIFICATION,
            items,
            change);
  }

  @Override
  @Transactional
  public ProfessionalProfileView add(AddCertificationCommand c) {
    return changes.add(
        c.accountId(),
        c.expectedVersion(),
        id ->
            Certification.of(
                id,
                c.name(),
                c.issuingOrganization(),
                c.issueDate(),
                c.expirationDate(),
                c.credentialUrl()));
  }

  @Override
  @Transactional
  public ProfessionalProfileView update(UpdateCertificationCommand c) {
    return changes.update(
        c.accountId(),
        c.expectedVersion(),
        c.itemId(),
        id ->
            Certification.of(
                id,
                c.name(),
                c.issuingOrganization(),
                c.issueDate(),
                c.expirationDate(),
                c.credentialUrl()));
  }

  @Override
  @Transactional
  public ProfessionalProfileView remove(RemoveCertificationCommand c) {
    return changes.remove(c.accountId(), c.expectedVersion(), c.itemId());
  }
}
