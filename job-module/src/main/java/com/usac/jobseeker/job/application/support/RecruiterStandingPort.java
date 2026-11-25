package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;

/** Puerto de salida: la confianza que se le tiene a la cuenta reclutadora al publicar. */
public interface RecruiterStandingPort {

  /**
   * El valor vigente. Una cuenta que ya no es reclutadora activa (se le retiró la capacidad entre
   * dos peticiones, o está en eliminación) se trata como `SUSPENDED`: ante la duda no se publica.
   */
  RecruiterVerification standingOf(AccountId accountId);
}
