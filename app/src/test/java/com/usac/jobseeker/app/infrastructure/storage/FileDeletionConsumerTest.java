package com.usac.jobseeker.app.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.outbox.OutboxEvent;
import com.usac.jobseeker.common.application.storage.FileDeletionRequested;
import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.rules.FileType;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileDeletionConsumerTest {

  private final List<StoredObjectKey> deleted = new ArrayList<>();
  private boolean down;

  private final ObjectStoragePort storage =
      new ObjectStoragePort() {
        @Override
        public StoredObjectKey put(FilePurpose purpose, byte[] content, FileType type) {
          throw new UnsupportedOperationException("el consumidor solo borra");
        }

        @Override
        public URI presignDownload(StoredObjectKey key, String displayName, FileType type) {
          throw new UnsupportedOperationException("el consumidor solo borra");
        }

        @Override
        public void delete(StoredObjectKey key) {
          if (down) {
            throw new UpstreamUnavailableException("storage", new IOException("caído"));
          }
          deleted.add(key);
        }

        @Override
        public boolean exists(StoredObjectKey key) {
          return !deleted.contains(key);
        }

        @Override
        public com.usac.jobseeker.common.application.storage.ObjectPage list(
            FilePurpose purpose, String startAfter, int maxKeys) {
          throw new UnsupportedOperationException("el consumidor solo borra");
        }
      };

  private final FileDeletionConsumer consumer = new FileDeletionConsumer(storage);

  @Test
  void everyKeyOfTheEventIsDeleted() {
    var first = StoredObjectKey.random(FilePurpose.CV);
    var second = StoredObjectKey.random(FilePurpose.CV);

    consumer.consume(event(FileDeletionRequested.payload(List.of(first, second))));

    assertThat(deleted).containsExactly(first, second);
    assertThat(consumer.handles(FileDeletionRequested.EVENT_TYPE)).isTrue();
    assertThat(consumer.handles("profile.anonymized")).isFalse();
  }

  // Si el almacén falla, la excepción sale para que el sondeador reintente: no se da por borrado.
  @Test
  void anUnavailableStorageFailsTheDeliverySoItIsRetried() {
    down = true;
    var event =
        event(FileDeletionRequested.payload(List.of(StoredObjectKey.random(FilePurpose.CV))));

    assertThatThrownBy(() -> consumer.consume(event))
        .isInstanceOf(UpstreamUnavailableException.class);
  }

  // Una clave mal formada no mejora reintentando: se salta y el resto se borra.
  @Test
  void aMalformedKeyIsSkippedWithoutBlockingTheOthers() {
    var good = StoredObjectKey.random(FilePurpose.CV);

    consumer.consume(event("{\"keys\":[\"../../secreto\",\"" + good.value() + "\"]}"));

    assertThat(deleted).containsExactly(good);
  }

  private static OutboxEvent event(String payload) {
    return new OutboxEvent(
        UUID.randomUUID(),
        "candidate_profile",
        UUID.randomUUID(),
        1,
        FileDeletionRequested.EVENT_TYPE,
        OutboxEvent.FIRST_EVENT_VERSION,
        payload,
        null);
  }
}
