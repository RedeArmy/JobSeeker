package com.usac.jobseeker.common.application.storage;

import com.usac.jobseeker.common.domain.rules.FileType;
import java.net.URI;

/**
 * Puerto de salida del almacén de archivos (ADR-0021 §1). Es técnico y compartido, como el de
 * métricas: cada módulo decide **qué** guarda y **quién** puede verlo; este puerto solo guarda,
 * firma y borra. `app` lo implementa sobre el protocolo S3 (SeaweedFS en desarrollo, Supabase
 * Storage en producción).
 *
 * <p>Si el almacén no responde, todos los métodos lanzan {@link
 * com.usac.jobseeker.common.domain.error.UpstreamUnavailableException}.
 */
public interface ObjectStoragePort {

  /**
   * Guarda el contenido, **ya validado y escaneado**, con una clave aleatoria nueva y el tipo MIME
   * del tipo detectado. Nunca reemplaza un objeto existente: los archivos son inmutables (§5).
   */
  StoredObjectKey put(FilePurpose purpose, byte[] content, FileType type);

  /**
   * Una URL de descarga firmada y de vida corta (como mucho 5 minutos, amenaza T-N10), que obliga a
   * descargar con este nombre y este tipo. Solo se pide **después** de autorizar, y la URL nunca se
   * guarda ni se registra: es un secreto al portador mientras vive (§3).
   */
  URI presignDownload(StoredObjectKey key, String displayName, FileType type);

  /** Borra el objeto. Borrar uno que ya no existe no es un error: el borrado se reintenta (§6). */
  void delete(StoredObjectKey key);

  boolean exists(StoredObjectKey key);
}
