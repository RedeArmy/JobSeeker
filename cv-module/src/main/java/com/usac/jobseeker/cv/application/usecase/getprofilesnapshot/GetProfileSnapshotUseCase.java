package com.usac.jobseeker.cv.application.usecase.getprofilesnapshot;

import java.util.Optional;

/**
 * Lectura del perfil de un candidato para otros módulos (`matching`, `job`, `ats`), siempre desde
 * su adaptador de salida y traducida a su propia proyección: ningún tipo de `cv` entra a su
 * dominio.
 */
public interface GetProfileSnapshotUseCase {

  /**
   * El perfil del candidato, o vacío si la cuenta no tiene la capacidad de candidato, o aún no creó
   * su perfil profesional o de candidato. «No hay perfil» no es un error: quien consume lo trata
   * como que no hay nada que calcular.
   */
  Optional<ProfileSnapshot> execute(GetProfileSnapshotQuery query);
}
