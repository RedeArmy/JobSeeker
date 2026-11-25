package com.usac.jobseeker.job.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.job.application.support.JobRequirementsStorePort;
import com.usac.jobseeker.job.domain.model.EducationLevel;
import com.usac.jobseeker.job.domain.model.JobPostingId;
import com.usac.jobseeker.job.domain.model.JobRequirement;
import com.usac.jobseeker.job.domain.model.LanguageProficiency;
import com.usac.jobseeker.job.domain.model.RequirementType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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
  public void replace(JobPostingId id, List<JobRequirement> requirements) {
    jdbc.sql("delete from job_requirements where job_posting_id = :posting")
        .param(POSTING, id.value())
        .update();
    for (int position = 0; position < requirements.size(); position++) {
      JobRequirement r = requirements.get(position);
      jdbc.sql(
              "insert into job_requirements (id, job_posting_id, requirement_type, is_mandatory,"
                  + " position, skill_name, min_level, education_level, min_years, language,"
                  + " min_proficiency) values (:id, :posting, :type, :mandatory, :position,"
                  + " :skillName, :minLevel, :education, :minYears, :language, :proficiency)")
          .param("id", UUID.randomUUID())
          .param(POSTING, id.value())
          .param("type", r.type().name())
          .param("mandatory", r.mandatory())
          .param("position", position)
          .param("skillName", r.skillName())
          .param("minLevel", r.minLevel())
          .param("education", r.educationLevel() == null ? null : r.educationLevel().name())
          .param("minYears", r.minYears())
          .param("language", r.language())
          .param("proficiency", r.minProficiency() == null ? null : r.minProficiency().name())
          .update();
    }
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
