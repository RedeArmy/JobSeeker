package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.cv.application.usecase.manageprojects.ProjectStorePort;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.Project;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de la escritura de los proyectos. Toda condición lleva el perfil dueño
 * (BR-ACCT-04).
 */
public class JdbcProjectRepository implements ProjectStorePort {

  private final JdbcClient jdbc;

  public JdbcProjectRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int count(ProfessionalProfileId profileId) {
    return ProfileItemTables.count(jdbc, ProfileItemTables.PROJECTS, profileId);
  }

  @Override
  public void insert(ProfessionalProfileId profileId, Project item) {
    jdbc.sql(
            "insert into projects (id, professional_profile_id, name, description, type, role, start_date, end_date, url) values (:id, :profile, :name, :description, :type, :role, :start, :end, :url)")
        .param("id", item.id().value())
        .param("profile", profileId.value())
        .param("name", item.name())
        .param("description", item.description())
        .param("type", item.type().name())
        .param("role", item.role())
        .param("start", item.startDate())
        .param("end", item.endDate())
        .param("url", item.url())
        .update();
  }

  @Override
  public boolean update(ProfessionalProfileId profileId, Project item) {
    return jdbc.sql(
                "update projects set name = :name, description = :description, type = :type, role = :role, start_date = :start, end_date = :end, url = :url where id = :id and professional_profile_id = :profile")
            .param("id", item.id().value())
            .param("profile", profileId.value())
            .param("name", item.name())
            .param("description", item.description())
            .param("type", item.type().name())
            .param("role", item.role())
            .param("start", item.startDate())
            .param("end", item.endDate())
            .param("url", item.url())
            .update()
        == 1;
  }

  @Override
  public boolean delete(ProfessionalProfileId profileId, ProfileItemId itemId) {
    return ProfileItemTables.delete(jdbc, ProfileItemTables.PROJECTS, profileId, itemId);
  }
}
