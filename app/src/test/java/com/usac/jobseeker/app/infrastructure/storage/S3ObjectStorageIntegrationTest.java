package com.usac.jobseeker.app.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.storage.FilePurpose;
import com.usac.jobseeker.common.application.storage.ObjectStoragePort;
import com.usac.jobseeker.common.application.storage.StoredObjectKey;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import com.usac.jobseeker.common.domain.rules.FileType;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * El adaptador S3 contra SeaweedFS real, el servidor de desarrollo y CI (ADR-0021, enmienda 1). Las
 * credenciales son obligatorias en el servidor, así que una firma alterada o caducada se rechaza de
 * verdad: es lo que protege una URL de descarga (amenaza T-N10).
 */
class S3ObjectStorageIntegrationTest {

  private static final String ACCESS = "prueba-acceso";
  private static final String SECRET = "prueba-secreto-1234567890";
  private static final int S3_PORT = 8333;

  private static final GenericContainer<?> SEAWEED =
      new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.53"))
          .withCopyToContainer(
              Transferable.of(
                  "{\"identities\":[{\"name\":\"prueba\",\"credentials\":[{\"accessKey\":\""
                      + ACCESS
                      + "\",\"secretKey\":\""
                      + SECRET
                      + "\"}],\"actions\":[\"Admin\",\"Read\",\"Write\",\"List\"]}]}"),
              "/etc/seaweedfs/s3.json")
          .withCommand(
              "server",
              "-s3",
              "-s3.port=" + S3_PORT,
              "-s3.config=/etc/seaweedfs/s3.json",
              "-dir=/data")
          .withExposedPorts(S3_PORT)
          // Sin credenciales, la raíz del S3 responde 403: señal de que la pasarela ya atiende.
          .waitingFor(Wait.forHttp("/").forPort(S3_PORT).forStatusCode(403))
          .withStartupTimeout(Duration.ofMinutes(2));

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static StorageConfiguration configuration;
  private static ObjectStoragePort storage;

  @BeforeAll
  static void start() {
    SEAWEED.start();
    configuration = new StorageConfiguration();
    storage = configuration.objectStorage(properties(endpoint(), Duration.ofMinutes(5)));
  }

  @AfterAll
  static void stop() throws Exception {
    if (storage instanceof AutoCloseable closeable) {
      closeable.close();
    }
    SEAWEED.stop();
  }

  @Test
  void aStoredFileCanBeDownloadedThroughItsSignedUrlAsAnAttachment() throws Exception {
    byte[] pdf = "%PDF-1.7\ncurrículum de prueba\n%%EOF".getBytes(StandardCharsets.UTF_8);

    StoredObjectKey key = storage.put(FilePurpose.CV, pdf, FileType.PDF);
    URI url = storage.presignDownload(key, "CV José Pérez.pdf", FileType.PDF);
    HttpResponse<byte[]> response = get(url);

    assertThat(key.value()).startsWith("cv/");
    assertThat(storage.exists(key)).isTrue();
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(pdf);
    assertThat(response.headers().firstValue("content-type")).contains("application/pdf");
    assertThat(response.headers().firstValue("content-disposition").orElseThrow())
        .startsWith("attachment;")
        .contains("filename*=UTF-8''CV%20Jos%C3%A9%20P%C3%A9rez.pdf");
  }

  // La firma protege la URL: cambiarla, o usarla tras su vida, no da acceso (amenaza T-N10).
  @Test
  void aTamperedOrExpiredSignatureIsRejected() throws Exception {
    StoredObjectKey key = storage.put(FilePurpose.CV, new byte[] {1, 2, 3}, FileType.PDF);
    String url = storage.presignDownload(key, "cv.pdf", FileType.PDF).toString();
    String tampered = url.replaceFirst("X-Amz-Signature=.", "X-Amz-Signature=" + other(url));

    ObjectStoragePort shortLived =
        configuration.objectStorage(properties(endpoint(), Duration.ofSeconds(1)));
    URI expiring = shortLived.presignDownload(key, "cv.pdf", FileType.PDF);
    Thread.sleep(2_500);

    assertThat(get(URI.create(tampered)).statusCode()).isEqualTo(403);
    assertThat(get(expiring).statusCode()).isEqualTo(403);
  }

  @Test
  void eachPurposeGetsItsOwnPrefixAndKeysNeverRepeat() {
    StoredObjectKey cv = storage.put(FilePurpose.CV, new byte[] {1}, FileType.PDF);
    StoredObjectKey photo = storage.put(FilePurpose.PHOTO, new byte[] {1}, FileType.PNG);
    StoredObjectKey again = storage.put(FilePurpose.CV, new byte[] {1}, FileType.PDF);

    assertThat(cv.value()).startsWith("cv/");
    assertThat(photo.value()).startsWith("photo/");
    assertThat(again).isNotEqualTo(cv);
  }

  // El borrado se reintenta por la bandeja (ADR-0021 §6): repetirlo no puede fallar.
  @Test
  void deletingIsIdempotent() {
    StoredObjectKey key = storage.put(FilePurpose.CV, new byte[] {9}, FileType.PDF);

    storage.delete(key);
    storage.delete(key);

    assertThat(storage.exists(key)).isFalse();
  }

  @Test
  void anUnreachableStorageIsUnavailableNotAnInternalError() throws Exception {
    int closedPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      closedPort = probe.getLocalPort();
    }
    ObjectStoragePort down =
        configuration.objectStorage(
            properties("http://127.0.0.1:" + closedPort, Duration.ofMinutes(5)));
    byte[] content = {1};

    assertThatThrownBy(() -> down.put(FilePurpose.CV, content, FileType.PDF))
        .isInstanceOf(UpstreamUnavailableException.class);
  }

  private static StorageProperties properties(String endpoint, Duration ttl) {
    return new StorageProperties(
        endpoint,
        "us-east-1",
        "jobseeker-test",
        ACCESS,
        SECRET,
        true,
        ttl,
        Duration.ofSeconds(2),
        Duration.ofSeconds(10),
        true);
  }

  private static String endpoint() {
    return "http://" + SEAWEED.getHost() + ":" + SEAWEED.getMappedPort(S3_PORT);
  }

  private static HttpResponse<byte[]> get(URI url) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  // Un carácter distinto del primero de la firma, para alterarla sin cambiar su longitud.
  private static String other(String url) {
    int at = url.indexOf("X-Amz-Signature=") + "X-Amz-Signature=".length();
    return url.charAt(at) == '0' ? "1" : "0";
  }
}
