package com.usac.jobseeker.job.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.job.application.support.JobRequirementsStorePort;
import com.usac.jobseeker.job.domain.model.EducationLevel;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.LanguageProficiency;
import com.usac.jobseeker.job.domain.model.RequirementType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `job_requirements`. El conjunto se reemplaza completo (borrar y volver a
 * insertar) dentro de la transacción del caso de uso, que ya avanzó la versión de la vacante: nunca
 * se edita un requisito suelto, así que el orden y las restricciones únicas se resuelven con un
 * solo patrón.
 */
public class JdbcJobRequirementsRepository implements JobRequirementsStorePort {

  private static final String POSTING = "posting";

  private final JdbcClient jdbc;

  public JdbcJobRequirementsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<JobRequirement> findByPosting(JobPostingId id) {
    return jdbc.sql(
            "select requirement_type, is_mandatory, skill_name, min_level, education_level,"
                + " min_years, language, min_proficiency from job_requirements"
                + " where job_posting_id = :posting order by position")
        .param(POSTING, id.value())
        .query((rows, n) -> requirementOf(rows))
        .list();
  }

  @Override
  public Map<JobPostingId, List<JobRequirement>> findByPostings(Collection<JobPostingId> ids) {
    Map<JobPostingId, List<JobRequirement>> found = new HashMap<>();
    if (ids.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "select job_posting_id, requirement_type, is_mandatory, skill_name, min_level,"
                + " education_level, min_years, language, min_proficiency from job_requirements"
                + " where job_posting_id = any(:ids) order by job_posting_id, position")
        .param("ids", ids.stream().map(JobPostingId::value).toArray(UUID[]::new))
        .query(
            (ResultSet rows) -> {
              found
                  .computeIfAbsent(
                      new JobPostingId(rows.getObject("job_posting_id", UUID.class)),
                      key -> new ArrayList<>())
                  .add(requirementOf(rows));
            });
    return found;
  }

  @Override
  public void replace(JobPostingId id, List<JobRequirement> requirements) {
    jdbc.sql("delete from job_requirements where job_posting_id = :posting")
        .param(POSTING, id.value())
        .update();
    if (requirements.isEmpty()) {
      return;
    }
    // TD-047: una sola sentencia para todo el conjunto, como `cv` (TD-R11), en lugar de una por
    // requisito. La posición sale del orden de los arreglos (`with ordinality`, que empieza en 1).
    jdbc.sql(
            "insert into job_requirements (id, job_posting_id, requirement_type, is_mandatory,"
                + " position, skill_name, min_level, education_level, min_years, language,"
                + " min_proficiency)"
                + " select id, :posting, t, m, ord - 1, sn, ml, ed, my, la, pr"
                + " from unnest(cast(:ids as uuid[]), cast(:types as text[]),"
                + " cast(:mandatory as boolean[]), cast(:skillNames as text[]),"
                + " cast(:minLevels as integer[]), cast(:educations as text[]),"
                + " cast(:minYears as integer[]), cast(:languages as text[]),"
                + " cast(:proficiencies as text[]))"
                + " with ordinality as x(id, t, m, sn, ml, ed, my, la, pr, ord)")
        .param(POSTING, id.value())
        .param("ids", requirements.stream().map(r -> UUID.randomUUID()).toArray(UUID[]::new))
        .param("types", requirements.stream().map(r -> r.type().name()).toArray(String[]::new))
        .param(
            "mandatory",
            requirements.stream().map(JobRequirement::mandatory).toArray(Boolean[]::new))
        .param(
            "skillNames",
            requirements.stream().map(JobRequirement::skillName).toArray(String[]::new))
        .param(
            "minLevels",
            requirements.stream().map(JobRequirement::minLevel).toArray(Integer[]::new))
        .param(
            "educations",
            requirements.stream()
                .map(r -> r.educationLevel() == null ? null : r.educationLevel().name())
                .toArray(String[]::new))
        .param(
            "minYears", requirements.stream().map(JobRequirement::minYears).toArray(Integer[]::new))
        .param(
            "languages", requirements.stream().map(JobRequirement::language).toArray(String[]::new))
        .param(
            "proficiencies",
            requirements.stream()
                .map(r -> r.minProficiency() == null ? null : r.minProficiency().name())
                .toArray(String[]::new))
        .update();
  }

  private static JobRequirement requirementOf(ResultSet rows) throws SQLException {
    String education = rows.getString("education_level");
    String proficiency = rows.getString("min_proficiency");
    return new JobRequirement(
        RequirementType.valueOf(rows.getString("requirement_type")),
        rows.getBoolean("is_mandatory"),
        rows.getString("skill_name"),
        smallint(rows, "min_level"),
        education == null ? null : EducationLevel.valueOf(education),
        smallint(rows, "min_years"),
        rows.getString("language"),
        proficiency == null ? null : LanguageProficiency.valueOf(proficiency));
  }

  private static Integer smallint(ResultSet rows, String column) throws SQLException {
    int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }
}
