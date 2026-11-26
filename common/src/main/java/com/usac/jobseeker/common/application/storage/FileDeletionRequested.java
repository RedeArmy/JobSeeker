package com.usac.jobseeker.common.application.storage;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Contrato del evento que pide borrar objetos del almacén (ADR-0021 §6). Lo emite el módulo dueño
 * del archivo en la misma transacción en que marca sus referencias como borradas (al anonimizar,
 * BR-DATA-05), y lo consume `app` por la bandeja con reintentos: el objeto se borra aunque el
 * almacén falle una vez, y nada se borra si la transacción se deshace.
 *
 * <p>La carga solo lleva claves aleatorias, que no son datos personales (ADR-0016).
 */
public final class FileDeletionRequested {

  public static final String EVENT_TYPE = "storage.file_deletion_requested";

  private FileDeletionRequested() {}

  /** `{"keys":["cv/…","cv/…"]}`; las claves ya validadas no necesitan escape. */
  public static String payload(Collection<StoredObjectKey> keys) {
    return keys.stream()
        .map(key -> "\"" + key.value() + "\"")
        .collect(Collectors.joining(",", "{\"keys\":[", "]}"));
  }
}
