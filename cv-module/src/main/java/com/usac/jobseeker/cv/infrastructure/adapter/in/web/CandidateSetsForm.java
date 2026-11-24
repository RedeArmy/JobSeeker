package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.cv.domain.model.CandidateLanguage;
import com.usac.jobseeker.cv.domain.model.CandidateSkill;
import com.usac.jobseeker.cv.domain.model.LanguageProficiency;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Traduce entre el texto de los cuadros de la pantalla (una línea por elemento) y los conjuntos del
 * dominio. Es solo formato de pantalla: las reglas de fondo las valida el dominio, y aquí solo se
 * rechaza lo que ni siquiera se puede leer, con el mismo tipo de error que el resto.
 */
final class CandidateSetsForm {

  private CandidateSetsForm() {}

  /** Una habilidad por línea: `Java` o `Java;4`. */
  static List<CandidateSkill> skills(String text) {
    List<FieldViolation> bad = new ArrayList<>();
    List<CandidateSkill> items = new ArrayList<>();
    List<String> lines = lines(text);
    for (int i = 0; i < lines.size(); i++) {
      String[] parts = lines.get(i).split(";", 2);
      Integer level = null;
      if (parts.length == 2 && !parts[1].isBlank()) {
        try {
          level = Integer.valueOf(parts[1].strip());
        } catch (NumberFormatException _) {
          bad.add(new FieldViolation("skills[" + i + "].level", "OUT_OF_RANGE"));
        }
      }
      items.add(new CandidateSkill(parts[0], level));
    }
    return orThrow(items, bad);
  }

  /** Un idioma por línea: `en;B2`. */
  static List<CandidateLanguage> languages(String text) {
    List<FieldViolation> bad = new ArrayList<>();
    List<CandidateLanguage> items = new ArrayList<>();
    List<String> lines = lines(text);
    for (int i = 0; i < lines.size(); i++) {
      String[] parts = lines.get(i).split(";", 2);
      LanguageProficiency level = null;
      if (parts.length == 2 && !parts[1].isBlank()) {
        try {
          level = LanguageProficiency.valueOf(parts[1].strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException _) {
          bad.add(new FieldViolation("languages[" + i + "].proficiency", "INVALID"));
        }
      }
      items.add(new CandidateLanguage(parts[0], level));
    }
    return orThrow(items, bad);
  }

  /** Países separados por comas, espacios o saltos de línea. */
  static List<String> countries(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    return Arrays.stream(text.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
  }

  static String skillsText(List<CandidateSkill> skills) {
    return String.join(
        "\n",
        skills.stream()
            .map(s -> s.level() == null ? s.name() : s.name() + ";" + s.level())
            .toList());
  }

  static String languagesText(List<CandidateLanguage> languages) {
    return String.join(
        "\n", languages.stream().map(l -> l.language() + ";" + l.proficiency()).toList());
  }

  private static List<String> lines(String text) {
    if (text == null) {
      return List.of();
    }
    return text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  private static <T> List<T> orThrow(List<T> items, List<FieldViolation> bad) {
    if (!bad.isEmpty()) {
      throw new ValidationFailedException(bad);
    }
    return items;
  }
}
