package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Construye la representación canónica de una vacante, el texto que se embebe (ADR-0017, decisión
 * 2). Sigue las mismas reglas que la del perfil:
 *
 * <ul>
 *   <li><b>Determinista:</b> los requisitos salen ordenados, así que el mismo contenido da siempre
 *       el mismo texto y el *hash* solo cambia si la vacante cambió (BR-MATCH-03).
 *   <li><b>Solo lo semántico:</b> título, descripción y requisitos. País, modalidad y salario son
 *       dimensiones estructurales del puntaje y no entran.
 *   <li><b>Acotada:</b> si excede el presupuesto de caracteres se acorta primero la descripción,
 *       que es lo largo, y en último caso se corta el final, y lo dice con `truncated` (ADR-0018,
 *       `truncate=false`).
 * </ul>
 *
 * <p>El texto sale como {@link EmbeddableText}, que pasa por la redacción: una descripción que
 * incluye un correo o un teléfono de contacto no llega al modelo.
 */
public final class CanonicalJobComposer {

  /** El texto listo para embeber y si hubo que reducirlo para que cupiera. */
  public record Composed(EmbeddableText text, boolean truncated) {}

  private static final int SHORT_DESCRIPTION = 2000;

  private CanonicalJobComposer() {}

  public static Composed compose(JobSignals signals, int maxCharacters) {
    String full = render(signals, Integer.MAX_VALUE);
    if (full.length() <= maxCharacters) {
      return new Composed(EmbeddableText.of(full), false);
    }
    String shortened = render(signals, SHORT_DESCRIPTION);
    if (shortened.length() <= maxCharacters) {
      return new Composed(EmbeddableText.of(shortened), true);
    }
    return new Composed(EmbeddableText.of(cutAtWord(shortened, maxCharacters)), true);
  }

  private static String render(JobSignals s, int descriptionLimit) {
    StringBuilder out = new StringBuilder();
    line(out, "Vacante", s.title());
    line(out, "Descripción", limited(s.description(), descriptionLimit));
    line(out, "Habilidades obligatorias", skills(s.requirements(), true));
    line(out, "Habilidades deseables", skills(s.requirements(), false));
    line(out, "Formación mínima", education(s.requirements()));
    line(out, "Experiencia mínima", years(s.requirements()));
    line(out, "Idiomas", languages(s.requirements()));
    return out.toString().strip();
  }

  private static String skills(List<JobSignals.Requirement> items, boolean mandatory) {
    return items.stream()
        .filter(r -> "SKILL".equals(r.type()) && r.mandatory() == mandatory)
        .sorted(Comparator.comparing(r -> r.skillName().toLowerCase(Locale.ROOT)))
        .map(
            r ->
                r.minLevel() == null
                    ? r.skillName()
                    : r.skillName() + " (nivel " + r.minLevel() + ")")
        .collect(Collectors.joining(", "));
  }

  private static String education(List<JobSignals.Requirement> items) {
    return items.stream()
        .filter(r -> "EDUCATION".equals(r.type()))
        .map(r -> r.educationLevel().toLowerCase(Locale.ROOT))
        .sorted()
        .collect(Collectors.joining(", "));
  }

  private static String years(List<JobSignals.Requirement> items) {
    return items.stream()
        .filter(r -> "EXPERIENCE_YEARS".equals(r.type()))
        .map(r -> r.minYears() + " años")
        .sorted()
        .collect(Collectors.joining(", "));
  }

  private static String languages(List<JobSignals.Requirement> items) {
    return items.stream()
        .filter(r -> "LANGUAGE".equals(r.type()))
        .sorted(Comparator.comparing(JobSignals.Requirement::language))
        .map(r -> r.language() + " " + r.minProficiency())
        .collect(Collectors.joining(", "));
  }

  private static String limited(String text, int limit) {
    String stripped = text.strip();
    return stripped.length() > limit ? cutAtWord(stripped, limit) : stripped;
  }

  private static void line(StringBuilder out, String label, String value) {
    if (value != null && !value.isBlank()) {
      out.append(label).append(": ").append(value.strip()).append('\n');
    }
  }

  private static String cutAtWord(String text, int max) {
    if (text.length() <= max) {
      return text;
    }
    int cut = text.lastIndexOf(' ', max);
    return text.substring(0, cut > max / 2 ? cut : max).strip();
  }
}
