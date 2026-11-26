package com.usac.jobseeker.common.application.storage;

import java.util.Locale;

/**
 * Para qué se guarda un archivo (ADR-0021 §1 y §4). Separa los objetos en el almacén y decide sus
 * límites, pero no dice de quién es: eso lo sabe el módulo dueño de la referencia.
 */
public enum FilePurpose {
  /** El CV de un candidato (`cv`, BR-CAND-01). */
  CV,
  /** La foto de perfil (`cv`). */
  PHOTO,
  /** Una respuesta de tipo archivo a una pregunta de postulación (`ats`, Ola 2, BR-APPQ-01). */
  ANSWER;

  /** El prefijo de sus claves en el almacén. */
  public String prefix() {
    return name().toLowerCase(Locale.ROOT);
  }
}
