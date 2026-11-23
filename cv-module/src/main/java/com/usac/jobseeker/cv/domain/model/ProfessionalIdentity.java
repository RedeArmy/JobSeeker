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
    String name = TextRules.singleLine("fullName", fullName, FULL_NAME_MAX, true, violations);
    String title = TextRules.singleLine("headline", headline, HEADLINE_MAX, false, violations);
    String about = TextRules.multiLine("bio", bio, BIO_MAX, violations);
    if (!violations.isEmpty()) {
      throw new ValidationFailedException(violations);
    }
    return new ProfessionalIdentity(name, title, about);
  }
}
