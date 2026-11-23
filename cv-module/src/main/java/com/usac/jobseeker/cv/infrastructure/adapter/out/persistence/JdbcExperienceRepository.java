package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.cv.application.usecase.manageexperiences.ExperienceStorePort;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de la escritura de las experiencias. Toda condición lleva el perfil dueño
 * (BR-ACCT-04).
 */
public class JdbcExperienceRepository implements ExperienceStorePort {

  private final JdbcClient jdbc;

  public JdbcExperienceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int count(ProfessionalProfileId profileId) {
    return ProfileItemTables.count(jdbc, ProfileItemTables.EXPERIENCES, profileId);
  }

  @Override
  public void insert(ProfessionalProfileId profileId, Experience item) {
    jdbc.sql(
            "insert into experiences (id, professional_profile_id, company, title, start_date, end_date, description) values (:id, :profile, :company, :title, :start, :end, :description)")
        .param("id", item.id().value())
        .param("profile", profileId.value())
        .param("company", item.company())
        .param("title", item.title())
        .param("start", item.startDate())
        .param("end", item.endDate())
        .param("description", item.description())
        .update();
  }

  @Override
  public boolean update(ProfessionalProfileId profileId, Experience item) {
    return jdbc.sql(
                "update experiences set company = :company, title = :title, start_date = :start, end_date = :end, description = :description where id = :id and professional_profile_id = :profile")
            .param("id", item.id().value())
            .param("profile", profileId.value())
            .param("company", item.company())
            .param("title", item.title())
            .param("start", item.startDate())
            .param("end", item.endDate())
            .param("description", item.description())
            .update()
        == 1;
  }

  @Override
  public boolean delete(ProfessionalProfileId profileId, ProfileItemId itemId) {
    return ProfileItemTables.delete(jdbc, ProfileItemTables.EXPERIENCES, profileId, itemId);
  }
}
