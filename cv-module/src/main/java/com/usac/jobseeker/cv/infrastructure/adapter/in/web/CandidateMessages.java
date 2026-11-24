package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El texto en español de los errores del perfil de candidato en pantalla. Traduce campo y regla a
 * una frase y, para los elementos de un conjunto (`skills[2].name`), dice cuál es por su posición
 * en la lista de la persona (la tercera línea). Nunca incluye el valor rechazado (BR-DATA-01).
 */
final class CandidateMessages {

  private static final Pattern ELEMENT =
      Pattern.compile("^(skills|languages|workAuthorizations)\\[(\\d+)](?:\\.(\\w+))?$");

  private static final Map<String, String> FIELDS =
      Map.ofEntries(
          Map.entry("contactEmail", "El correo de contacto"),
          Map.entry("contactPhone", "El teléfono"),
          Map.entry("countryOfResidence", "El país de residencia"),
          Map.entry("salaryMin", "El salario mínimo"),
          Map.entry("salaryMax", "El salario máximo"),
          Map.entry("salaryCurrency", "La moneda del salario"),
          Map.entry("salaryPeriod", "El periodo del salario"),
          Map.entry("version", "La versión"),
          Map.entry("status", "El estado de disponibilidad"),
          Map.entry("discoverable", "La descubribilidad"),
          Map.entry("accountId", "La cuenta"),
          Map.entry("exclusions", "La lista de exclusión"),
          Map.entry("professionalProfile", "Tu perfil profesional"),
          Map.entry("skills", "Las habilidades"),
          Map.entry("languages", "Los idiomas"),
          Map.entry("workAuthorizations", "Las autorizaciones de trabajo"));

  private static final Map<String, String> PARTS =
      Map.of(
          "name",
          "el nombre",
          "level",
          "el nivel",
          "language",
          "el código",
          "proficiency",
          "el nivel");

  private static final Map<String, String> RULES =
      Map.ofEntries(
          Map.entry("REQUIRED", "es obligatorio."),
          Map.entry("TOO_LONG", "es demasiado largo."),
          Map.entry("INVALID_CHARACTERS", "contiene caracteres que no se permiten."),
          Map.entry("INVALID_EMAIL", "no parece un correo válido."),
          Map.entry("INVALID_PHONE", "solo admite dígitos y separadores."),
          Map.entry("INVALID_COUNTRY", "no es un código de país válido (por ejemplo GT)."),
          Map.entry("INVALID_LANGUAGE", "no es un código de idioma válido (por ejemplo es)."),
          Map.entry("INVALID_CURRENCY", "no es una moneda válida (por ejemplo USD)."),
          Map.entry("OUT_OF_RANGE", "está fuera del rango permitido."),
          Map.entry("RANGE_INVERTED", "no puede ser menor que el mínimo."),
          Map.entry("TOO_MANY_DECIMALS", "admite como máximo dos decimales."),
          Map.entry("DUPLICATE", "está repetido."),
          Map.entry("SELF_EXCLUSION", "no puede ser tu propia cuenta."),
          Map.entry("TOO_MANY", "superó el máximo permitido."));

  private CandidateMessages() {}

  static List<String> describe(List<FieldViolation> violations) {
    return violations.stream().map(CandidateMessages::one).toList();
  }

  private static String one(FieldViolation v) {
    String rule = RULES.getOrDefault(v.code(), "no es válido.");
    Matcher element = ELEMENT.matcher(v.field());
    if (element.matches()) {
      String part =
          element.group(3) == null
              ? ""
              : " (" + PARTS.getOrDefault(element.group(3), "un dato") + ")";
      int position = Integer.parseInt(element.group(2)) + 1;
      return "La línea "
          + position
          + " de "
          + FIELDS.get(element.group(1)).toLowerCase(java.util.Locale.ROOT)
          + part
          + " "
          + rule;
    }
    return FIELDS.getOrDefault(v.field(), "Un dato") + " " + rule;
  }
}
