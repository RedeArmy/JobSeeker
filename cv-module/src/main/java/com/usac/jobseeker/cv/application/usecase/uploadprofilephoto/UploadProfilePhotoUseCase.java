package com.usac.jobseeker.cv.application.usecase.uploadprofilephoto;

import com.usac.jobseeker.cv.application.usecase.getmyprofilephoto.ProfilePhotoView;

/**
 * Sube la foto del perfil profesional de quien llama y reemplaza la anterior, que se borra del
 * almacén por la bandeja (ADR-0021, `tdd/cv-module.md` §10.8).
 *
 * <p>Lanza {@code NotFoundException} sin perfil profesional, {@code FileRejectedException} con el
 * motivo y {@code UpstreamUnavailableException} si el almacén o el antivirus no responden.
 */
public interface UploadProfilePhotoUseCase {
  ProfilePhotoView execute(UploadProfilePhotoCommand command);
}
