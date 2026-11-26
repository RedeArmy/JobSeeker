package com.usac.jobseeker.app.infrastructure.storage;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.ObjectPage;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.rules.FileType;
import java.io.IOException;
import java.net.URI;

/**
 * El almacén cuando no está configurado: cada operación responde 503. Así la aplicación arranca sin
 * almacén (pruebas, desarrollo sin SeaweedFS) y el resto funciona, pero nada finge haber guardado
 * un archivo (ADR-0021 §7). Es preferible a construir el cliente S3 sin `endpoint`, que apuntaría a
 * AWS.
 */
final class UnavailableObjectStorage implements ObjectStoragePort {

  @Override
  public StoredObjectKey put(FilePurpose purpose, byte[] content, FileType type) {
    throw unavailable();
  }

  @Override
  public URI presignDownload(StoredObjectKey key, String displayName, FileType type) {
    throw unavailable();
  }

  @Override
  public void delete(StoredObjectKey key) {
    throw unavailable();
  }

  @Override
  public boolean exists(StoredObjectKey key) {
    throw unavailable();
  }

  @Override
  public ObjectPage list(FilePurpose purpose, String startAfter, int maxKeys) {
    throw unavailable();
  }

  private static UpstreamUnavailableException unavailable() {
    return new UpstreamUnavailableException(
        "storage", new IOException("almacén de archivos no configurado"));
  }
}
