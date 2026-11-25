package com.usac.jobseeker.auth.application.usecase.changerecruiterverification;

/** Puerto de entrada: fija la verificación de una cuenta reclutadora. */
public interface ChangeRecruiterVerificationUseCase {

  /**
   * @return `true` si cambió, `false` si ya tenía ese valor (idempotente)
   * @throws com.usac.jobseeker.common.domain.error.NotFoundException si la cuenta no existe o no es
   *     reclutadora: verificar a quien no lo es no tendría efecto y escondería un error del
   *     llamador
   */
  boolean execute(ChangeRecruiterVerificationCommand command);
}
