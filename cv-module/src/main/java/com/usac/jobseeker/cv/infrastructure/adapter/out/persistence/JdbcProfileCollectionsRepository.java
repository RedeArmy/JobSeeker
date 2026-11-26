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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lectura de las cuatro colecciones del perfil (`data-model.md` §4.3). La escritura de cada una
 * vive en su propio adaptador, porque cada puerto cuenta y borra en **su** tabla.
 */
public class JdbcProfileCollectionsRepository implements ProfileCollectionsReadPort {

  private static final String PROFILES = "profiles";
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
    return findAllByProfileIds(List.of(profileId)).get(profileId);
  }

  // Cuatro consultas para todos los perfiles pedidos, agrupadas por dueño (TD-039); la individual
  // es
  // el caso de uno. Un perfil sin elementos tiene colecciones vacías, no está ausente.
  @Override
  public Map<ProfessionalProfileId, ProfileCollections> findAllByProfileIds(
      Collection<ProfessionalProfileId> profileIds) {
    UUID[] ids = profileIds.stream().map(ProfessionalProfileId::value).toArray(UUID[]::new);
    Map<UUID, List<Experience>> experiences = experiences(ids);
    Map<UUID, List<Education>> education = education(ids);
    Map<UUID, List<Certification>> certifications = certifications(ids);
    Map<UUID, List<Project>> projects = projects(ids);
    Map<ProfessionalProfileId, ProfileCollections> all = new HashMap<>();
    for (ProfessionalProfileId profile : profileIds) {
      UUID id = profile.value();
      all.put(
          profile,
          new ProfileCollections(
              experiences.getOrDefault(id, List.of()),
              education.getOrDefault(id, List.of()),
              certifications.getOrDefault(id, List.of()),
              projects.getOrDefault(id, List.of())));
    }
    return all;
  }

  // Lo vigente va primero: una experiencia actual (sin fecha de fin) o un estudio en curso son lo
  // que más interesa, y después lo más reciente.
  private Map<UUID, List<Experience>> experiences(UUID[] profiles) {
    Map<UUID, List<Experience>> byProfile = new HashMap<>();
    jdbc.sql(
            "select id, professional_profile_id, company, title, start_date, end_date, description"
                + " from experiences where professional_profile_id = any(:profiles)"
                + " order by end_date desc nulls first, start_date desc, id")
        .param(PROFILES, profiles)
        .query(
            (rows, n) ->
                byProfile
                    .computeIfAbsent(owner(rows), k -> new ArrayList<>())
                    .add(
                        new Experience(
                            new ProfileItemId(rows.getObject(ID, UUID.class)),
                            rows.getString("company"),
                            rows.getString("title"),
                            rows.getObject(START_DATE, LocalDate.class),
                            rows.getObject(END_DATE, LocalDate.class),
                            rows.getString(DESCRIPTION))))
        .list();
    return byProfile;
  }

  private Map<UUID, List<Education>> education(UUID[] profiles) {
    Map<UUID, List<Education>> byProfile = new HashMap<>();
    jdbc.sql(
            "select id, professional_profile_id, institution, degree, level, completion_date"
                + " from education_entries where professional_profile_id = any(:profiles)"
                + " order by completion_date desc nulls first, id")
        .param(PROFILES, profiles)
        .query(
            (rows, n) ->
                byProfile
                    .computeIfAbsent(owner(rows), k -> new ArrayList<>())
                    .add(
                        new Education(
                            new ProfileItemId(rows.getObject(ID, UUID.class)),
                            rows.getString("institution"),
                            rows.getString("degree"),
                            rows.getString("level") == null
                                ? null
                                : EducationLevel.valueOf(rows.getString("level")),
                            rows.getObject("completion_date", LocalDate.class))))
        .list();
    return byProfile;
  }

  private Map<UUID, List<Certification>> certifications(UUID[] profiles) {
    Map<UUID, List<Certification>> byProfile = new HashMap<>();
    jdbc.sql(
            "select id, professional_profile_id, name, issuing_organization, issue_date,"
                + " expiration_date, credential_url from certifications"
                + " where professional_profile_id = any(:profiles)"
                + " order by issue_date desc nulls last, id")
        .param(PROFILES, profiles)
        .query(
            (rows, n) ->
                byProfile
                    .computeIfAbsent(owner(rows), k -> new ArrayList<>())
                    .add(
                        new Certification(
                            new ProfileItemId(rows.getObject(ID, UUID.class)),
                            rows.getString("name"),
                            rows.getString("issuing_organization"),
                            rows.getObject("issue_date", LocalDate.class),
                            rows.getObject("expiration_date", LocalDate.class),
                            rows.getString("credential_url"))))
        .list();
    return byProfile;
  }

  private Map<UUID, List<Project>> projects(UUID[] profiles) {
    Map<UUID, List<Project>> byProfile = new HashMap<>();
    jdbc.sql(
            "select id, professional_profile_id, name, description, type, role, start_date, end_date,"
                + " url from projects where professional_profile_id = any(:profiles)"
                + " order by end_date desc nulls first, start_date desc nulls last, id")
        .param(PROFILES, profiles)
        .query(
            (rows, n) ->
                byProfile
                    .computeIfAbsent(owner(rows), k -> new ArrayList<>())
                    .add(
                        new Project(
                            new ProfileItemId(rows.getObject(ID, UUID.class)),
                            rows.getString("name"),
                            rows.getString(DESCRIPTION),
                            ProjectType.valueOf(rows.getString("type")),
                            rows.getString("role"),
                            rows.getObject(START_DATE, LocalDate.class),
                            rows.getObject(END_DATE, LocalDate.class),
                            rows.getString("url"))))
        .list();
    return byProfile;
  }

  private static UUID owner(java.sql.ResultSet rows) throws java.sql.SQLException {
    return rows.getObject("professional_profile_id", UUID.class);
  }
}
