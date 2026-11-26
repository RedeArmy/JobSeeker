package com.usac.jobseeker.app.infrastructure.storage;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.outbox.OutboxEventConsumer;
import com.usac.jobseeker.common.application.storage.FileDeletionRequested;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Borra del almacén los objetos que un módulo pidió borrar al anonimizar (ADR-0021 §6). Vive en
 * `app` porque el almacén es infraestructura compartida y cualquier módulo puede pedirlo (`cv` hoy,
 * `ats` en la Ola 2).
 *
 * <p>Es idempotente: borrar un objeto que ya no existe no es un error. Si el almacén falla, la
 * excepción hace que el sondeador reintente con retroceso; nunca se da por hecho un borrado que no
 * ocurrió. Una clave mal formada no mejora con reintentos: se registra y se salta.
 */
final class FileDeletionConsumer implements OutboxEventConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(FileDeletionConsumer.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ObjectStoragePort storage;

  FileDeletionConsumer(ObjectStoragePort storage) {
    this.storage = Objects.requireNonNull(storage, "storage");
  }

  @Override
  public String name() {
    return "delete-stored-files";
  }

  @Override
  public boolean handles(String eventType) {
    return FileDeletionRequested.EVENT_TYPE.equals(eventType);
  }

  @Override
  public void consume(OutboxEvent event) {
    int deleted = 0;
    for (JsonNode key : JSON.readTree(event.payload()).path("keys")) {
      try {
        storage.delete(new StoredObjectKey(key.asString()));
        deleted++;
      } catch (IllegalArgumentException e) {
        LOG.error("event=file_deletion_invalid_key Clave de almacenamiento no válida en el evento");
      }
    }
    LOG.info("event=files_deleted count={} Archivos borrados del almacén", deleted);
  }
}
