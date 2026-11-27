package com.usac.jobseeker.cv.application.usecase.removeprofilephoto;

/**
 * Quita la foto del perfil profesional de quien llama y pide su borrado del almacén por la bandeja.
 * Quitar una foto que no hay no es un error. Lanza {@code NotFoundException} sin perfil
 * profesional.
 */
public interface RemoveProfilePhotoUseCase {
  void execute(RemoveProfilePhotoCommand command);
}
