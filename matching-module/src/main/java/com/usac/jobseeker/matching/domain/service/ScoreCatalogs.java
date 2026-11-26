package com.usac.jobseeker.matching.domain.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Los catálogos cerrados que ordenan los niveles de formación e idioma, y la normalización de
 * nombres. Los valores son los de `cv` y `job`; aquí solo se les da un orden, que es lo que una
 * comparación necesita. Un valor que no está en el catálogo no tiene rango: no se compara.
 */
final class ScoreCatalogs {

  private static final List<String> EDUCATION =
      List.of(
          "SECONDARY", "TECHNICAL", "BACHELOR", "POSTGRAD_SPECIALIZATION", "MASTERS", "DOCTORATE");
  private static final List<String> PROFICIENCY =
      List.of("A1", "A2", "B1", "B2", "C1", "C2", "NATIVE");
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NOT_A_NAME_CHARACTER = Pattern.compile("[^a-z0-9+#]+");

  private ScoreCatalogs() {}

  // `List.of` lanza ante nulo en `indexOf`: un nivel sin declarar es un caso normal, no un error.
  /** Rango de 1 en adelante, o 0 si el valor es nulo o no está en el catálogo. */
  static int educationRank(String level) {
    return level == null ? 0 : EDUCATION.indexOf(level) + 1;
  }

  static int proficiencyRank(String proficiency) {
    return proficiency == null ? 0 : PROFICIENCY.indexOf(proficiency) + 1;
  }

  /**
   * La clave con que se comparan nombres escritos por personas: sin mayúsculas, acentos ni
   * puntuación, para que «Node.js», «node js» y «NODEJS» no sean tres habilidades. Sigue siendo
   * coincidencia por texto: la normalización por taxonomía es TD-038 (ADR-0019).
   */
  static String nameKey(String raw) {
    if (raw == null) {
      return "";
    }
    String lower = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
    String plain = MARKS.matcher(lower).replaceAll("");
    return NOT_A_NAME_CHARACTER.matcher(plain).replaceAll("");
  }
}
