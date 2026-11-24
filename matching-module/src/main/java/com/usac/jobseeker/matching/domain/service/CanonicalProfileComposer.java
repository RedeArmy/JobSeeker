package com.usac.jobseeker.matching.domain.service;

import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Construye la representación canónica de un perfil, el texto que se embebe (ADR-0017, decisión 2).
 *
 * <ul>
 *   <li><b>Determinista:</b> las mismas señales dan siempre el mismo texto, sea cual sea el orden
 *       en que `cv` las entregue; si no, el *hash* cambiaría sin que el perfil cambie y se llamaría
 *       a Ollama de más (BR-MATCH-03).
 *   <li><b>Solo lo semántico:</b> titular, habilidades, experiencia, formación, certificaciones,
 *       proyectos e idiomas. País, modalidad, jornada, reubicación y salario son dimensiones
 *       estructurales del puntaje y no entran: cambiarlos recalcula el puntaje sin volver a
 *       embeber. Las autorizaciones de trabajo tampoco.
 *   <li><b>Sin identidad:</b> el tipo de entrada no trae nombre, contacto ni instituciones, y el
 *       texto libre pasa por la redacción antes de salir (devuelve un {@link EmbeddableText}).
 *   <li><b>Acotada:</b> si excede el presupuesto de caracteres se reduce con una estrategia fija y
 *       registrada (ADR-0018, `truncate=false`): primero se acortan las descripciones, luego se
 *       quitan las de proyectos y, en último caso, se corta el final. El resultado lo dice con
 *       `truncated`.
 * </ul>
 */
public final class CanonicalProfileComposer {

  /** El texto listo para embeber y si hubo que reducirlo para que cupiera. */
  public record Composed(EmbeddableText text, boolean truncated) {}

  private static final int SHORT_DESCRIPTION = 400;

  private CanonicalProfileComposer() {}

  public static Composed compose(CandidateSignals signals, int maxCharacters) {
    String full = render(signals, Integer.MAX_VALUE, true);
    if (full.length() <= maxCharacters) {
      return new Composed(EmbeddableText.of(full), false);
    }
    String shortened = render(signals, SHORT_DESCRIPTION, true);
    if (shortened.length() <= maxCharacters) {
      return new Composed(EmbeddableText.of(shortened), true);
    }
    String withoutProjectText = render(signals, SHORT_DESCRIPTION, false);
    if (withoutProjectText.length() <= maxCharacters) {
      return new Composed(EmbeddableText.of(withoutProjectText), true);
    }
    return new Composed(EmbeddableText.of(cutAtWord(withoutProjectText, maxCharacters)), true);
  }

  private static String render(CandidateSignals s, int descriptionLimit, boolean projectText) {
    StringBuilder out = new StringBuilder();
    line(out, "Titular", s.headline());
    line(out, "Habilidades", skills(s.skills()));
    section(out, "Experiencia", experiences(s.experiences(), descriptionLimit));
    section(out, "Formación", education(s.education()));
    line(out, "Certificaciones", sortedJoin(s.certifications()));
    section(out, "Proyectos", projects(s.projects(), descriptionLimit, projectText));
    line(out, "Idiomas", languages(s.languages()));
    return out.toString().strip();
  }

  private static String skills(List<CandidateSignals.Skill> skills) {
    return skills.stream()
        .sorted(
            Comparator.comparing((CandidateSignals.Skill k) -> k.name().toLowerCase(Locale.ROOT)))
        .map(k -> k.level() == null ? k.name() : k.name() + " (nivel " + k.level() + ")")
        .collect(Collectors.joining(", "));
  }

  private static List<String> experiences(List<CandidateSignals.Experience> items, int limit) {
    return items.stream()
        .sorted(
            Comparator.comparing(
                    CandidateSignals.Experience::startDate,
                    Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()))
                .thenComparing(CandidateSignals.Experience::title))
        .map(
            e ->
                e.title()
                    + " ("
                    + period(e.startDate(), e.endDate())
                    + ")"
                    + described(e.description(), limit))
        .toList();
  }

  private static List<String> education(List<CandidateSignals.Education> items) {
    return items.stream()
        .sorted(Comparator.comparing(CandidateSignals.Education::degree))
        .map(e -> e.degree() + (e.level() == null ? "" : ", " + e.level().toLowerCase(Locale.ROOT)))
        .toList();
  }

  private static List<String> projects(
      List<CandidateSignals.Project> items, int limit, boolean withText) {
    return items.stream()
        .sorted(Comparator.comparing(CandidateSignals.Project::name))
        .map(
            p ->
                p.name()
                    + (p.role() == null ? "" : " (" + p.role() + ")")
                    + (withText ? described(p.description(), limit) : ""))
        .toList();
  }

  private static String languages(List<CandidateSignals.Language> items) {
    return items.stream()
        .sorted(Comparator.comparing(CandidateSignals.Language::language))
        .map(l -> l.language() + " " + l.proficiency())
        .collect(Collectors.joining(", "));
  }

  private static String sortedJoin(List<String> items) {
    return items.stream().sorted().collect(Collectors.joining(", "));
  }

  private static String period(LocalDate start, LocalDate end) {
    String from = start == null ? "" : String.valueOf(start.getYear());
    String to = end == null ? "actual" : String.valueOf(end.getYear());
    return from + " - " + to;
  }

  private static String described(String description, int limit) {
    if (description == null || description.isBlank()) {
      return "";
    }
    String text = description.strip();
    return ": " + (text.length() > limit ? cutAtWord(text, limit) : text);
  }

  private static void line(StringBuilder out, String label, String value) {
    if (value != null && !value.isBlank()) {
      out.append(label).append(": ").append(value.strip()).append('\n');
    }
  }

  private static void section(StringBuilder out, String label, List<String> items) {
    if (!items.isEmpty()) {
      out.append(label).append(":\n");
      items.forEach(item -> out.append("- ").append(item).append('\n'));
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
