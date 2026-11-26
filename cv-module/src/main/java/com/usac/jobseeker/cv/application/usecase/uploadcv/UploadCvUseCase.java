package com.usac.jobseeker.cv.application.usecase.uploadcv;

import com.usac.jobseeker.cv.application.usecase.getmycv.CvFileView;

/**
 * Sube una versión del CV de quien llama y la deja vigente (ADR-0021, `tdd/cv-module.md` §10.7).
 * Subir el mismo archivo que el vigente no crea otra versión.
 *
 * <p>Lanza {@code ForbiddenCapabilityException} sin la capacidad de candidato, {@code
 * NotFoundException} sin perfil de candidato, {@code FileRejectedException} con el motivo, y {@code
 * UpstreamUnavailableException} si el almacén o el antivirus no responden.
 */
public interface UploadCvUseCase {
  CvFileView execute(UploadCvCommand command);
}
