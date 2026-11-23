package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.cv.application.usecase.manageeducation.EducationStorePort;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de la escritura de las formaciones. Toda condición lleva el perfil dueño
 * (BR-ACCT-04).
 */
public class JdbcEducationRepository implements EducationStorePort {

  private final JdbcClient jdbc;

  public JdbcEducationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int count(ProfessionalProfileId profileId) {
    return ProfileItemTables.count(jdbc, ProfileItemTables.EDUCATION, profileId);
  }

  @Override
  public void insert(ProfessionalProfileId profileId, Education item) {
    jdbc.sql(
            "insert into education_entries (id, professional_profile_id, institution, degree, level, completion_date) values (:id, :profile, :institution, :degree, :level, :completion)")
        .param("id", item.id().value())
        .param("profile", profileId.value())
        .param("institution", item.institution())
        .param("degree", item.degree())
        .param("level", item.level() == null ? null : item.level().name())
        .param("completion", item.completionDate())
        .update();
  }

  @Override
  public boolean update(ProfessionalProfileId profileId, Education item) {
    return jdbc.sql(
                "update education_entries set institution = :institution, degree = :degree, level = :level, completion_date = :completion where id = :id and professional_profile_id = :profile")
            .param("id", item.id().value())
            .param("profile", profileId.value())
            .param("institution", item.institution())
            .param("degree", item.degree())
            .param("level", item.level() == null ? null : item.level().name())
            .param("completion", item.completionDate())
            .update()
        == 1;
  }

  @Override
  public boolean delete(ProfessionalProfileId profileId, ProfileItemId itemId) {
    return ProfileItemTables.delete(jdbc, ProfileItemTables.EDUCATION, profileId, itemId);
  }
}
