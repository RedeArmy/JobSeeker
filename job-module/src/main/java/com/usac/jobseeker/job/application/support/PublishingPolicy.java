package com.usac.jobseeker.job.application.support;

import com.usac.jobseeker.common.domain.model.RecruiterVerification;

/**
 * Cuántas vacantes puede tener publicadas a la vez una cuenta según su confianza (REQ-R-10). Son
 * topes de configuración (`jobseeker.job.publishing.*`), no valores fijos: se calibran con el uso
 * real. Contar solo las `PUBLISHED` deja libre el cupo al cerrar, que es lo que el reclutador
 * espera, y reabrir una cerrada pasa por el mismo tope.
 *
 * @param unverifiedMaxPublished tope sin verificar; 0 impide publicar hasta verificarse
 * @param verifiedMaxPublished tope verificada; nunca menor que el de sin verificar, o verificarse
 *     empeoraría la cuenta
 */
public record PublishingPolicy(int unverifiedMaxPublished, int verifiedMaxPublished) {

  public PublishingPolicy {
    if (unverifiedMaxPublished < 0 || verifiedMaxPublished < unverifiedMaxPublished) {
      throw new IllegalArgumentException(
          "los topes de publicación no son coherentes: 0 <= sin verificar <= verificada");
    }
  }

  /** El tope de una cuenta; una suspendida no tiene cupo. */
  public int limitFor(RecruiterVerification standing) {
    return switch (standing) {
      case UNVERIFIED -> unverifiedMaxPublished;
      case VERIFIED -> verifiedMaxPublished;
      case SUSPENDED -> 0;
    };
  }
}
