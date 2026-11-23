package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.cv.application.usecase.getmyprofessionalprofile.ProfileCollectionsReadPort;
import com.usac.jobseeker.cv.domain.model.Certification;
import com.usac.jobseeker.cv.domain.model.Education;
import com.usac.jobseeker.cv.domain.model.EducationLevel;
import com.usac.jobseeker.cv.domain.model.Experience;
import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileCollections;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import com.usac.jobseeker.cv.domain.model.Project;
import com.usac.jobseeker.cv.domain.model.ProjectType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lectura de las cuatro colecciones del perfil (`data-model.md` §4.3). La escritura de cada una
 * vive en su propio adaptador, porque cada puerto cuenta y borra en **su** tabla.
 */
public class JdbcProfileCollectionsRepository implements ProfileCollectionsReadPort {

  private static final String PROFILE = "profile";
  private static final String ID = "id";
  private static final String START_DATE = "start_date";
  private static final String END_DATE = "end_date";
  private static final String DESCRIPTION = "description";

  private final JdbcClient jdbc;

  public JdbcProfileCollectionsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ---- lectura de las cuatro colecciones ----

  @Override
  public ProfileCollections findByProfileId(ProfessionalProfileId profileId) {
    UUID profile = profileId.value();
    return new ProfileCollections(
        experiences(profile), education(profile), certifications(profile), projects(profile));
  }

  // Lo vigente va primero: una experiencia actual (sin fecha de fin) o un estudio en curso son lo
  // que
  // más interesa, y después lo más reciente.
  private List<Experience> experiences(UUID profile) {
    return jdbc.sql(
            "select id, company, title, start_date, end_date, description from experiences"
                + " where professional_profile_id = :profile"
                + " order by end_date desc nulls first, start_date desc, id")
        .param(PROFILE, profile)
        .query(
            (rows, n) ->
                new Experience(
                    new ProfileItemId(rows.getObject(ID, UUID.class)),
                    rows.getString("company"),
                    rows.getString("title"),
                    rows.getObject(START_DATE, LocalDate.class),
                    rows.getObject(END_DATE, LocalDate.class),
                    rows.getString(DESCRIPTION)))
        .list();
  }

  private List<Education> education(UUID profile) {
    return jdbc.sql(
            "select id, institution, degree, level, completion_date from education_entries"
                + " where professional_profile_id = :profile"
                + " order by completion_date desc nulls first, id")
        .param(PROFILE, profile)
        .query(
            (rows, n) ->
                new Education(
                    new ProfileItemId(rows.getObject(ID, UUID.class)),
                    rows.getString("institution"),
                    rows.getString("degree"),
                    rows.getString("level") == null
                        ? null
                        : EducationLevel.valueOf(rows.getString("level")),
                    rows.getObject("completion_date", LocalDate.class)))
        .list();
  }

  private List<Certification> certifications(UUID profile) {
    return jdbc.sql(
            "select id, name, issuing_organization, issue_date, expiration_date, credential_url"
                + " from certifications where professional_profile_id = :profile"
                + " order by issue_date desc nulls last, id")
        .param(PROFILE, profile)
        .query(
            (rows, n) ->
                new Certification(
                    new ProfileItemId(rows.getObject(ID, UUID.class)),
                    rows.getString("name"),
                    rows.getString("issuing_organization"),
                    rows.getObject("issue_date", LocalDate.class),
                    rows.getObject("expiration_date", LocalDate.class),
                    rows.getString("credential_url")))
        .list();
  }

  private List<Project> projects(UUID profile) {
    return jdbc.sql(
            "select id, name, description, type, role, start_date, end_date, url from projects"
                + " where professional_profile_id = :profile"
                + " order by end_date desc nulls first, start_date desc nulls last, id")
        .param(PROFILE, profile)
        .query(
            (rows, n) ->
                new Project(
                    new ProfileItemId(rows.getObject(ID, UUID.class)),
                    rows.getString("name"),
                    rows.getString(DESCRIPTION),
                    ProjectType.valueOf(rows.getString("type")),
                    rows.getString("role"),
                    rows.getObject(START_DATE, LocalDate.class),
                    rows.getObject(END_DATE, LocalDate.class),
                    rows.getString("url")))
        .list();
  }
}
