package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.rules.FormatRules;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Reglas de validación que comparten todos los datos del perfil profesional: textos, fechas y
 * enlaces. Cada regla agrega su violación a la lista en vez de lanzar, para que quien valida pueda
 * reunir **todos** los errores de un formulario y devolverlos juntos. Nunca incluyen el valor
 * rechazado, que puede ser un dato personal (BR-DATA-01).
 */
final class TextRules {

  static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
  static final LocalDate LATEST = LocalDate.of(2100, 12, 31);
  static final int URL_MAX = 2000;

  // Códigos estables de violación: los usa la API y los traduce la pantalla.
  private static final String END_BEFORE_START = "END_BEFORE_START";
  private static final String INVALID_URL = "INVALID_URL";

  private TextRules() {}

  /** Fecha dentro de un rango razonable, que atrapa años mal tecleados (0202, 20255). */
  static LocalDate date(
      String field, LocalDate value, boolean required, List<FieldViolation> violations) {
    if (value == null) {
      if (required) {
        violations.add(new FieldViolation(field, FormatRules.REQUIRED));
      }
      return null;
    }
    if (value.isBefore(EARLIEST) || value.isAfter(LATEST)) {
      violations.add(new FieldViolation(field, FormatRules.OUT_OF_RANGE));
    }
    return value;
  }

  /**
   * El fin no puede ser anterior al inicio; si falta alguno de los dos no hay nada que comparar.
   */
  static void order(
      String endField, LocalDate start, LocalDate end, List<FieldViolation> violations) {
    if (start != null && end != null && end.isBefore(start)) {
      violations.add(new FieldViolation(endField, END_BEFORE_START));
    }
  }

  /**
   * Enlace opcional. Solo `http` y `https` con un servidor: se rechazan `javascript:`, `data:` y
   * similares, que el perfil mostraría como un enlace en que otra persona pulsa.
   */
  static String url(String field, String raw, List<FieldViolation> violations) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty()) {
      return null;
    }
    if (value.codePointCount(0, value.length()) > URL_MAX) {
      violations.add(new FieldViolation(field, FormatRules.TOO_LONG));
    } else if (!isWebUrl(value)) {
      violations.add(new FieldViolation(field, INVALID_URL));
    }
    return value;
  }

  private static boolean isWebUrl(String value) {
    if (value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
      return false;
    }
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      return (scheme.equals("http") || scheme.equals("https"))
          && uri.getHost() != null
          && !uri.getHost().isBlank();
    } catch (URISyntaxException _) {
      return false;
    }
  }
}
