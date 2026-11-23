package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.cv.application.usecase.managecertifications.CertificationStorePort;
import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de la escritura de las certificaciones. Toda condición lleva el perfil dueño
 * (BR-ACCT-04).
 */
public class JdbcCertificationRepository implements CertificationStorePort {

  private final JdbcClient jdbc;

  public JdbcCertificationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int count(ProfessionalProfileId profileId) {
    return ProfileItemTables.count(jdbc, ProfileItemTables.CERTIFICATIONS, profileId);
  }

  @Override
  public void insert(ProfessionalProfileId profileId, Certification item) {
    jdbc.sql(
            "insert into certifications (id, professional_profile_id, name, issuing_organization, issue_date, expiration_date, credential_url) values (:id, :profile, :name, :issuer, :issued, :expires, :url)")
        .param("id", item.id().value())
        .param("profile", profileId.value())
        .param("name", item.name())
        .param("issuer", item.issuingOrganization())
        .param("issued", item.issueDate())
        .param("expires", item.expirationDate())
        .param("url", item.credentialUrl())
        .update();
  }

  @Override
  public boolean update(ProfessionalProfileId profileId, Certification item) {
    return jdbc.sql(
                "update certifications set name = :name, issuing_organization = :issuer, issue_date = :issued, expiration_date = :expires, credential_url = :url where id = :id and professional_profile_id = :profile")
            .param("id", item.id().value())
            .param("profile", profileId.value())
            .param("name", item.name())
            .param("issuer", item.issuingOrganization())
            .param("issued", item.issueDate())
            .param("expires", item.expirationDate())
            .param("url", item.credentialUrl())
            .update()
        == 1;
  }

  @Override
  public boolean delete(ProfessionalProfileId profileId, ProfileItemId itemId) {
    return ProfileItemTables.delete(jdbc, ProfileItemTables.CERTIFICATIONS, profileId, itemId);
  }
}
