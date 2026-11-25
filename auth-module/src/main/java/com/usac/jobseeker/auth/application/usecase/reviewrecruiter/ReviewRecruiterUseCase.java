package com.usac.jobseeker.auth.application.usecase.reviewrecruiter;

/**
 * Puerto de entrada: un operador verifica, suspende o devuelve a «sin verificar» a un reclutador
 * (BR-ACCT-11).
 */
public interface ReviewRecruiterUseCase {

  /**
   * @return `true` si la verificación cambió, `false` si ya tenía ese valor (idempotente)
   * @throws com.usac.jobseeker.common.domain.error.NotFoundException si quien llama no es operador
   *     (el canal no existe para él) o si la cuenta objetivo no es reclutadora
   * @throws com.usac.jobseeker.common.domain.error.ValidationFailedException si el operador se
   *     apunta a sí mismo: nadie se verifica a sí mismo
   */
  boolean execute(ReviewRecruiterCommand command);
}
