package com.usac.jobseeker.cv.infrastructure.adapter.in.web;

import com.usac.jobseeker.cv.domain.model.EducationLevel;

/**
 * Los textos en español de los valores de catálogo que la pantalla muestra. El código interno
 * (`POSTGRAD_SPECIALIZATION`) es para las reglas de elegibilidad y la API, no para una persona. La
 * plantilla las llama como métodos estáticos; es público porque Thymeleaf lo exige.
 */
public final class ProfileLabels {

  private ProfileLabels() {}

  public static String level(EducationLevel level) {
    return switch (level) {
      case SECONDARY -> "Educación media";
      case TECHNICAL -> "Técnico";
      case BACHELOR -> "Licenciatura o grado";
      case POSTGRAD_SPECIALIZATION -> "Especialización o postgrado";
      case MASTERS -> "Maestría";
      case DOCTORATE -> "Doctorado";
    };
  }
}
