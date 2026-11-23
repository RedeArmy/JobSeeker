package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cómo se presenta una persona profesionalmente: nombre, titular y biografía. Es el núcleo que
 * comparten todas las capacidades de la cuenta (BR-ACCT-02, BR-ACCT-06).
 *
 * <p>Se construye solo por {@link #of}, que normaliza y valida; así un perfil con datos inválidos
 * no puede existir en memoria ni llegar a la base. Reúne todas las violaciones y nunca incluye el
 * valor rechazado en ellas (BR-DATA-01).
 */
public record ProfessionalIdentity(String fullName, String headline, String bio) {

  public static final int FULL_NAME_MAX = 200;
  public static final int HEADLINE_MAX = 200;
  public static final int BIO_MAX = 4000;

  /**
   * @param fullName obligatorio
   * @param headline opcional; un texto en blanco equivale a no haberlo dado
   * @param bio opcional, puede tener varias líneas
   * @throws ValidationFailedException con todas las reglas incumplidas
   */
  public static ProfessionalIdentity of(String fullName, String headline, String bio) {
    List<FieldViolation> violations = new ArrayList<>();
    String name = singleLine("fullName", fullName, FULL_NAME_MAX, true, violations);
    String title = singleLine("headline", headline, HEADLINE_MAX, false, violations);
    String about = multiLine("bio", bio, BIO_MAX, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return new ProfessionalIdentity(name, title, about);
  }

  private static String singleLine(
      String field, String raw, int max, boolean required, List<FieldViolation> violations) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty()) {
      if (required) {
        violations.add(new FieldViolation(field, "REQUIRED"));
      }
      return null;
    }
    // Un salto de línea en un nombre o titular no tiene sentido y rompe listados y correos.
    if (hasForbiddenControl(value, false)) {
      violations.add(new FieldViolation(field, "INVALID_CHARACTERS"));
    } else if (value.codePointCount(0, value.length()) > max) {
      violations.add(new FieldViolation(field, "TOO_LONG"));
    }
    return value;
  }

  private static String multiLine(
      String field, String raw, int max, List<FieldViolation> violations) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty()) {
      return null;
    }
    if (hasForbiddenControl(value, true)) {
      violations.add(new FieldViolation(field, "INVALID_CHARACTERS"));
    } else if (value.codePointCount(0, value.length()) > max) {
      violations.add(new FieldViolation(field, "TOO_LONG"));
    }
    return value;
  }

  // PostgreSQL no admite el carácter NUL en un `text`: sin esta regla llegaría como un 500 en vez
  // de
  // un 400. Se rechazan todos los de control salvo los saltos de línea y tabulaciones de la
  // biografía.
  private static boolean hasForbiddenControl(String value, boolean allowLayout) {
    return value
        .codePoints()
        .anyMatch(
            c ->
                Character.isISOControl(c)
                    && !(allowLayout && (c == '\n' || c == '\r' || c == '\t')));
  }
}
