package com.usac.jobseeker.common.application.storage;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * La clave de un objeto en el almacén: `{propósito}/{uuid aleatorio}` (ADR-0021 §2). No lleva la
 * cuenta ni el nombre original, para que una clave filtrada no diga de quién es ni qué contiene, y
 * no se puede adivinar (amenaza T-N10). Los módulos la guardan como una referencia opaca.
 */
public record StoredObjectKey(String value) {

  private static final Pattern FORMAT =
      Pattern.compile(
          "^(cv|photo|answer)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  public StoredObjectKey {
    Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("Clave de almacenamiento con formato no válido");
    }
  }

  /** Una clave nueva y aleatoria para ese propósito; la genera el adaptador al guardar. */
  public static StoredObjectKey random(FilePurpose purpose) {
    return new StoredObjectKey(purpose.prefix() + "/" + UUID.randomUUID());
  }
}
