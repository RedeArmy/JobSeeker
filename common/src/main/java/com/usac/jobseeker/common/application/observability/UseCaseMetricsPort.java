package com.usac.jobseeker.common.application.observability;

/**
 * Puerto de salida con el que un caso de uso expone su métrica de negocio (ADR-0012). `domain` y
 * `application` no pueden depender de Micrometer (ARCH-01/02), así que el caso de uso solo dice qué
 * ocurrió y `app` lo traduce al contador `jobseeker_usecase_total`.
 *
 * <p>Solo admite el nombre del caso de uso y un resultado, ambos de un conjunto cerrado y en
 * inglés: nunca un identificador ni un dato personal, que además dispararían la cardinalidad de la
 * métrica (BR-DATA-01).
 */
public interface UseCaseMetricsPort {

  /**
   * @param useCase nombre estable del caso de uso, en `snake_case` (p. ej. `get_my_account`)
   * @param outcome resultado de un conjunto cerrado (p. ej. `found`, `not_found`)
   */
  void count(String useCase, String outcome);
}
