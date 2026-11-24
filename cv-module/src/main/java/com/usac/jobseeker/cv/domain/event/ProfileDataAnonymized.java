package com.usac.jobseeker.cv.domain.event;

import com.usac.jobseeker.common.domain.model.AccountId;

/**
 * Los datos de `cv` de una cuenta se anonimizaron (BR-DATA-05): es el `AccountAnonymized` del TDD
 * de `cv`, con un nombre que no se confunde con el evento de cuenta de `auth`. `matching` anula el
 * bloque de embedding de esa cuenta y fuerza su copia de `discoverable` a `false` al recibirlo, y
 * cualquier score en caché con datos derivados del perfil debe invalidarse.
 *
 * <p>Solo lleva el identificador de cuenta y la versión del agregado: nunca nada del perfil.
 */
public final class ProfileDataAnonymized {

  public static final String EVENT_TYPE = "profile.anonymized";

  private ProfileDataAnonymized() {}

  /** El JSON de la carga; todo son UUID y un número, sin nada que escapar. */
  public static String payload(AccountId accountId, long version) {
    return "{\"accountId\":\"" + accountId + "\",\"version\":" + version + "}";
  }
}
