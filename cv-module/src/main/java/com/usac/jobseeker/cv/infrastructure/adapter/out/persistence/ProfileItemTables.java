package com.usac.jobseeker.cv.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.cv.domain.model.ProfessionalProfileId;
import com.usac.jobseeker.cv.domain.model.ProfileItemId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lo que es idéntico en las cuatro tablas de colecciones: contar y borrar por perfil dueño. Solo se
 * acepta una de las cuatro tablas conocidas y cada una tiene su consulta completa.
 */
final class ProfileItemTables {

  static final String EXPERIENCES = "experiences";
  static final String EDUCATION = "education_entries";
  static final String CERTIFICATIONS = "certifications";
  static final String PROJECTS = "projects";

  private ProfileItemTables() {}

  static int count(JdbcClient jdbc, String table, ProfessionalProfileId profileId) {
    return jdbc.sql(countSql(table))
        .param("profile", profileId.value())
        .query(Integer.class)
        .single();
  }

  // El perfil dueño va en la condición: un elemento de otro perfil no coincide y se informa como
  // inexistente, nunca se borra (BR-ACCT-04).
  static boolean delete(
      JdbcClient jdbc, String table, ProfessionalProfileId profileId, ProfileItemId itemId) {
    return jdbc.sql(deleteSql(table))
            .param("id", itemId.value())
            .param("profile", profileId.value())
            .update()
        == 1;
  }

  // Cada tabla tiene su consulta completa y fija: no se arma texto SQL con ningún valor, ni
  // siquiera
  // con un nombre de tabla de una lista cerrada, así no hay nada que inyectar ni que revisar.
  private static String countSql(String table) {
    return switch (table) {
      case EXPERIENCES ->
          "select count(*) from experiences where professional_profile_id = :profile";
      case EDUCATION ->
          "select count(*) from education_entries where professional_profile_id = :profile";
      case CERTIFICATIONS ->
          "select count(*) from certifications where professional_profile_id = :profile";
      case PROJECTS -> "select count(*) from projects where professional_profile_id = :profile";
      default -> throw new IllegalArgumentException("tabla desconocida");
    };
  }

  private static String deleteSql(String table) {
    return switch (table) {
      case EXPERIENCES ->
          "delete from experiences where id = :id and professional_profile_id = :profile";
      case EDUCATION ->
          "delete from education_entries where id = :id and professional_profile_id = :profile";
      case CERTIFICATIONS ->
          "delete from certifications where id = :id and professional_profile_id = :profile";
      case PROJECTS -> "delete from projects where id = :id and professional_profile_id = :profile";
      default -> throw new IllegalArgumentException("tabla desconocida");
    };
  }
}
