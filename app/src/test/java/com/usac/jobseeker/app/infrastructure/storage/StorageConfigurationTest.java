package com.usac.jobseeker.app.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.rules.FileType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class StorageConfigurationTest {

  private final StorageConfiguration configuration = new StorageConfiguration();

  // Sin configuración la aplicación arranca, pero nada finge guardar ni escanear (ADR-0021 §7).
  @Test
  void withoutConfigurationEveryOperationIsUnavailable() {
    var storage = configuration.objectStorage(storage(null));
    var scanner =
        configuration.malwareScanner(
            new MalwareScanProperties(
                null, 3310, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024),
            new SimpleMeterRegistry());
    var key = StoredObjectKey.random(FilePurpose.CV);
    var content = new byte[] {1};

    assertThatThrownBy(() -> storage.put(FilePurpose.CV, content, FileType.PDF))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(() -> storage.presignDownload(key, "cv.pdf", FileType.PDF))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(() -> storage.get(key)).isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(() -> storage.exists(key)).isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(() -> scanner.scan(content))
        .isInstanceOf(UpstreamUnavailableException.class);
  }

  // UNKNOWN no baja el estado general: la prueba de humo sin almacén sigue en UP.
  @Test
  void anUnconfiguredDependencyReportsUnknownHealthNotDown() {
    var storage = configuration.objectStorage(storage(""));
    var scanner =
        configuration.malwareScanner(
            new MalwareScanProperties("", 3310, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024),
            new SimpleMeterRegistry());

    assertThat(configuration.storageHealthIndicator(storage).health(true).getStatus())
        .isEqualTo(Status.UNKNOWN);
    assertThat(configuration.malwareScanHealthIndicator(scanner).health(true).getStatus())
        .isEqualTo(Status.UNKNOWN);
  }

  @Test
  void aConfiguredButUnreachableScannerIsDown() throws Exception {
    int closed;
    try (var probe = new java.net.ServerSocket(0)) {
      closed = probe.getLocalPort();
    }
    var scanner =
        configuration.malwareScanner(
            new MalwareScanProperties(
                "127.0.0.1", closed, Duration.ofMillis(300), Duration.ofSeconds(1), 1024),
            new SimpleMeterRegistry());

    assertThat(configuration.malwareScanHealthIndicator(scanner).health(true).getStatus())
        .isEqualTo(Status.DOWN);
  }

  @Test
  void theDownloadLifetimeCannotExceedFiveMinutes() {
    Duration tooLong = Duration.ofMinutes(6);

    assertThatThrownBy(() -> storageWithTtl(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("5 minutos");
    assertThatThrownBy(() -> storageWithTtl(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(storageWithTtl(Duration.ofMinutes(5)).downloadTtl()).hasMinutes(5);
  }

  // A medio configurar es un error de despliegue que se ve al arrancar, no un almacén «ausente».
  @Test
  void anEndpointWithoutCredentialsFailsAtStartup() {
    Duration ttl = Duration.ofMinutes(5);
    Duration connect = Duration.ofSeconds(2);
    Duration read = Duration.ofSeconds(10);

    assertThatThrownBy(
            () ->
                new StorageProperties(
                    "http://almacen:8333",
                    "us-east-1",
                    "b",
                    null,
                    null,
                    true,
                    ttl,
                    connect,
                    read,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("access-key");
  }

  @Test
  void theScannerSettingsAreValidated() {
    Duration second = Duration.ofSeconds(1);

    assertThatThrownBy(() -> new MalwareScanProperties("h", 0, second, second, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MalwareScanProperties("h", 3310, second, second, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theAttachmentHeaderKeepsAccentsAndHasAnAsciiFallback() {
    assertThat(S3ObjectStorage.attachment("CV José.pdf"))
        .isEqualTo("attachment; filename=\"CV Jos_.pdf\"; filename*=UTF-8''CV%20Jos%C3%A9.pdf");
  }

  private static StorageProperties storage(String endpoint) {
    return new StorageProperties(
        endpoint,
        "us-east-1",
        null,
        null,
        null,
        true,
        Duration.ofMinutes(5),
        Duration.ofSeconds(2),
        Duration.ofSeconds(10),
        false);
  }

  private static StorageProperties storageWithTtl(Duration ttl) {
    return new StorageProperties(
        null,
        "us-east-1",
        null,
        null,
        null,
        true,
        ttl,
        Duration.ofSeconds(2),
        Duration.ofSeconds(10),
        false);
  }
}
