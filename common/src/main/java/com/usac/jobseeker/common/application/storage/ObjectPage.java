package com.usac.jobseeker.common.application.storage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Una página del listado del almacén, en orden de clave. Las claves vienen **crudas**: el almacén
 * puede tener objetos que no creó la aplicación, y quien lista decide qué hacer con una clave que
 * no reconoce (nunca borrarla, `tdd/cv-module.md` §10.7).
 *
 * @param truncated hay más objetos después del último de esta página
 */
public record ObjectPage(List<Entry> objects, boolean truncated) {

  public ObjectPage {
    objects = List.copyOf(objects);
  }

  /** Clave del último objeto, para pedir la página siguiente; vacía si la página no trae nada. */
  public String lastKey() {
    return objects.isEmpty() ? "" : objects.get(objects.size() - 1).key();
  }

  /**
   * @param key clave tal como está en el almacén
   * @param lastModified cuándo se escribió; decide si un objeto es lo bastante viejo para juzgarlo
   */
  public record Entry(String key, Instant lastModified) {
    public Entry {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(lastModified, "lastModified");
    }
  }
}
