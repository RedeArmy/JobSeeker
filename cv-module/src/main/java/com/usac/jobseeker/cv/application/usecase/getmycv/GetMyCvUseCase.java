package com.usac.jobseeker.cv.application.usecase.getmycv;

/**
 * El CV de quien llama. Lanza {@code ForbiddenCapabilityException} sin la capacidad de candidato y
 * {@code NotFoundException} si aún no hay perfil de candidato.
 */
public interface GetMyCvUseCase {
  MyCvView execute(GetMyCvQuery query);
}
