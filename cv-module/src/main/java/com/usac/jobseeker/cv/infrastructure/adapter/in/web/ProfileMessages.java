package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import java.util.List;
import java.util.Map;

/**
 * El texto en español de los errores de validación que ve la persona en pantalla. La API devuelve
 * códigos estables y campos; la interfaz los traduce aquí. Nunca incluye el valor rechazado: el
 * error dice qué campo y qué regla, no qué escribió la persona (BR-DATA-01).
 */
final class ProfileMessages {

  private static final Map<String, String> FIELDS =
      Map.ofEntries(
          Map.entry("fullName", "El nombre"),
          Map.entry("headline", "El titular"),
          Map.entry("bio", "La biografía"),
          Map.entry("company", "La empresa"),
          Map.entry("title", "El puesto"),
          Map.entry("description", "La descripción"),
          Map.entry("startDate", "La fecha de inicio"),
          Map.entry("endDate", "La fecha de fin"),
          Map.entry("institution", "La institución"),
          Map.entry("degree", "El título"),
          Map.entry("level", "El nivel"),
          Map.entry("completionDate", "La fecha de finalización"),
          Map.entry("name", "El nombre"),
          Map.entry("issuingOrganization", "La organización emisora"),
          Map.entry("issueDate", "La fecha de emisión"),
          Map.entry("expirationDate", "La fecha de expiración"),
          Map.entry("credentialUrl", "El enlace de la credencial"),
          Map.entry("type", "El tipo"),
          Map.entry("role", "El rol"),
          Map.entry("url", "El enlace"),
          Map.entry("version", "La versión"),
          Map.entry("experiences", "Las experiencias"),
          Map.entry("education", "Las formaciones"),
          Map.entry("certifications", "Las certificaciones"),
          Map.entry("projects", "Los proyectos"));

  private static final Map<String, String> RULES =
      Map.of(
          "REQUIRED", "es obligatorio.",
          "TOO_LONG", "es demasiado largo.",
          "INVALID_CHARACTERS", "contiene caracteres que no se permiten.",
          "OUT_OF_RANGE", "tiene una fecha fuera del rango permitido (1900 a 2100).",
          "END_BEFORE_START", "no puede ser anterior a la fecha de inicio.",
          "INVALID_URL", "debe ser una dirección que empiece por http:// o https://.",
          "TOO_MANY", "ya alcanzó el máximo de 50 elementos.",
          "INVALID", "no es válido.");

  private ProfileMessages() {}

  static List<String> describe(List<FieldViolation> violations) {
    return violations.stream()
        .map(
            v ->
                FIELDS.getOrDefault(v.field(), "Un dato")
                    + " "
                    + RULES.getOrDefault(v.code(), "no es válido."))
        .toList();
  }
}
