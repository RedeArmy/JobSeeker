package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lo que es idéntico en las cuatro tablas de colecciones: contar y borrar por perfil dueño. El
 * nombre de la tabla se concatena en la consulta, así que **solo** se acepta uno de los cuatro
 * conocidos; nunca llega un dato externo ahí.
 */
final class ProfileItemTables {

  static final String EXPERIENCES = "experiences";
  static final String EDUCATION = "education_entries";
  static final String CERTIFICATIONS = "certifications";
  static final String PROJECTS = "projects";

  private ProfileItemTables() {}

  static int count(JdbcClient jdbc, String table, ProfessionalProfileId profileId) {
    return jdbc.sql(
            "select count(*) from " + known(table) + " where professional_profile_id = :profile")
        .param("profile", profileId.value())
        .query(Integer.class)
        .single();
  }

  // El perfil dueño va en la condición: un elemento de otro perfil no coincide y se informa como
  // inexistente, nunca se borra (BR-ACCT-04).
  static boolean delete(
      JdbcClient jdbc, String table, ProfessionalProfileId profileId, ProfileItemId itemId) {
    return jdbc.sql(
                "delete from "
                    + known(table)
                    + " where id = :id and professional_profile_id = :profile")
            .param("id", itemId.value())
            .param("profile", profileId.value())
            .update()
        == 1;
  }

  private static String known(String table) {
    return switch (table) {
      case EXPERIENCES, EDUCATION, CERTIFICATIONS, PROJECTS -> table;
      default -> throw new IllegalArgumentException("tabla desconocida");
    };
  }
}
