package com.usac.jobseeker.cv.domain.model;

import com.usac.jobseeker.common.domain.error.FieldViolation;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import com.usac.jobseeker.common.domain.rules.FormatRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * El contacto con el que el candidato postula (BR-CAND-01). Puede diferir del correo de acceso de
 * la cuenta, que vive en Clavaris. Es un dato personal: nunca va a un log ni a una etiqueta de
 * métrica (BR-DATA-01) y la anonimización lo borra (BR-DATA-05).
 */
public record ContactInfo(String email, String phone) {

  private static final String EMAIL_FIELD = "contactEmail";
  public static final int EMAIL_MAX = 254;
  public static final int PHONE_MAX = 30;

  // Una comprobación de forma, no de existencia: algo@dominio.tld sin espacios. Verificar que el
  // buzón exista exigiría enviar un correo, y eso es de otro caso de uso.
  private static final Pattern EMAIL_SHAPE =
      Pattern.compile("^[^@\\s]++@[^@\\s.]++(?:\\.[^@\\s.]++)++$");
  private static final Pattern PHONE_SHAPE = Pattern.compile("^[0-9+()\\-. ]+$");

  public ContactInfo {
    Objects.requireNonNull(email, "email");
  }

  static ContactInfo of(String email, String phone, List<FieldViolation> violations) {
    String cleanEmail = email == null ? "" : email.strip();
    if (cleanEmail.isEmpty()) {
      violations.add(new FieldViolation(EMAIL_FIELD, FormatRules.REQUIRED));
    } else if (cleanEmail.codePointCount(0, cleanEmail.length()) > EMAIL_MAX) {
      violations.add(new FieldViolation(EMAIL_FIELD, FormatRules.TOO_LONG));
    } else if (FormatRules.hasForbiddenControl(cleanEmail, false)
        || !EMAIL_SHAPE.matcher(cleanEmail).matches()) {
      violations.add(new FieldViolation(EMAIL_FIELD, "INVALID_EMAIL"));
    }
    String stripped = phone == null ? "" : phone.strip();
    if (stripped.length() > PHONE_MAX) {
      violations.add(new FieldViolation("contactPhone", FormatRules.TOO_LONG));
    } else if (!stripped.isEmpty() && !PHONE_SHAPE.matcher(stripped).matches()) {
      violations.add(new FieldViolation("contactPhone", "INVALID_PHONE"));
    }
    // Un teléfono en blanco es «no lo dio»: se guarda como ausente, no como cadena vacía.
    String cleanPhone = stripped.isEmpty() ? null : stripped;
    return new ContactInfo(cleanEmail, cleanPhone);
  }

  /** Atajo para quien construye solo el contacto y quiere el error ya lanzado. */
  public static ContactInfo validated(String email, String phone) {
    List<FieldViolation> violations = new ArrayList<>();
    ContactInfo contact = of(email, phone, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return contact;
  }
}
