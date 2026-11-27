package com.usac.jobseeker.cv.application.usecase.getmyprofilephoto;

import java.util.Optional;

/** La foto del perfil profesional de quien llama. Vacía si no tiene perfil o no subió ninguna. */
public interface GetMyProfilePhotoUseCase {

  /** Si hay foto y su etiqueta para la caché, sin leer el almacén. */
  Optional<ProfilePhotoView> describe(GetMyProfilePhotoQuery query);

  /** El contenido para servirlo; lanza {@code UpstreamUnavailableException} si el almacén falla. */
  Optional<ProfilePhotoContent> read(GetMyProfilePhotoQuery query);
}
