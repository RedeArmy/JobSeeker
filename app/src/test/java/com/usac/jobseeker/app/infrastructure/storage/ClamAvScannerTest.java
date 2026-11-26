package com.usac.jobseeker.app.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.storage.MalwareScanPort.ScanVerdict;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * El cliente de `clamd` contra un servidor falso en un *socket* local: comprueba el protocolo
 * `INSTREAM` que recibe el servidor y cómo se interpreta cada respuesta. El contrato con ClamAV
 * real lo cubre {@code ClamAvContractTest}.
 */
class ClamAvScannerTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private ServerSocket server;
  private CompletableFuture<byte[]> received;

  @AfterEach
  void stop() throws IOException {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void theContentTravelsInLengthPrefixedChunksEndedByAZeroChunk() throws Exception {
    serve(payload -> "stream: OK");
    byte[] content = new byte[150_000];
    content[149_999] = 7;

    var verdict = scanner(64 * 1024).scan(content);

    assertThat(verdict).isInstanceOf(ScanVerdict.Clean.class);
    assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo(content);
    assertThat(meters.get("jobseeker.malware.scan").tag("outcome", "clean").timer().count())
        .isOne();
  }

  @Test
  void aFoundReplyIsInfectedWithItsSignature() throws Exception {
    serve(payload -> "stream: Eicar-Test-Signature FOUND");

    var verdict = scanner(1024).scan("X5O!P%@AP".getBytes(StandardCharsets.US_ASCII));

    assertThat(verdict).isEqualTo(new ScanVerdict.Infected("Eicar-Test-Signature"));
  }

  // Fail-closed (ADR-0021 §7): lo que no es OK ni FOUND no puede tomarse por «limpio».
  @Test
  void anyOtherReplyIsUnavailableNeverClean() throws Exception {
    serve(payload -> "INSTREAM size limit exceeded. ERROR");

    var scanner = scanner(1024);
    var content = new byte[10];

    assertThatThrownBy(() -> scanner.scan(content))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(meters.get("jobseeker.malware.scan").tag("outcome", "unavailable").timer().count())
        .isOne();
  }

  @Test
  void theVerdictParserAcceptsOnlyTheTwoKnownForms() {
    assertThat(ClamAvScanner.verdictOf("stream: OK")).isInstanceOf(ScanVerdict.Clean.class);
    assertThat(ClamAvScanner.verdictOf("stream: Win.Test.EICAR_HDB-1 FOUND"))
        .isEqualTo(new ScanVerdict.Infected("Win.Test.EICAR_HDB-1"));
    for (String reply : new String[] {"", "OK", "stream: FOUND", "PONG", "stream: OKAY"}) {
      assertThatThrownBy(() -> ClamAvScanner.verdictOf(reply))
          .as(reply)
          .isInstanceOf(UpstreamUnavailableException.class);
    }
  }

  @Test
  void aServerThatNeverAnswersIsUnavailableAfterTheReadTimeout() throws Exception {
    server = new ServerSocket(0);
    CompletableFuture.runAsync(this::acceptAndHang);
    var scanner =
        new ClamAvScanner(
            new MalwareScanProperties(
                "127.0.0.1",
                server.getLocalPort(),
                Duration.ofSeconds(1),
                Duration.ofMillis(300),
                1024),
            meters);
    var content = new byte[10];
    long start = System.nanoTime();

    assertThatThrownBy(() -> scanner.scan(content))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);
  }

  @Test
  void aClosedPortIsUnavailable() throws Exception {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    var scanner =
        new ClamAvScanner(
            new MalwareScanProperties(
                "127.0.0.1", port, Duration.ofMillis(500), Duration.ofSeconds(1), 1024),
            meters);
    var content = new byte[10];

    assertThatThrownBy(() -> scanner.scan(content))
        .isInstanceOf(UpstreamUnavailableException.class)
        .satisfies(
            e ->
                assertThat(((UpstreamUnavailableException) e).dependency())
                    .isEqualTo("malware_scan"));
  }

  @Test
  void thePingExpectsPong() throws Exception {
    serve(payload -> "PONG");
    var scanner = scanner(1024);

    org.assertj.core.api.Assertions.assertThatCode(scanner::ping).doesNotThrowAnyException();
  }

  private ClamAvScanner scanner(int chunkSize) {
    return new ClamAvScanner(
        new MalwareScanProperties(
            "127.0.0.1",
            server.getLocalPort(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            chunkSize),
        meters);
  }

  // Un clamd falso de una sola conexión: lee el comando, reconstruye el contenido y responde.
  private void serve(Function<byte[], String> reply) throws IOException {
    server = new ServerSocket(0);
    received = new CompletableFuture<>();
    CompletableFuture.runAsync(
        () -> {
          try (Socket socket = server.accept()) {
            var in = new DataInputStream(socket.getInputStream());
            String command = readCommand(in);
            byte[] payload = command.equals("zINSTREAM") ? readChunks(in) : new byte[0];
            received.complete(payload);
            socket
                .getOutputStream()
                .write((reply.apply(payload) + "\0").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
          } catch (IOException e) {
            received.completeExceptionally(e);
          }
        });
  }

  private void acceptAndHang() {
    try (Socket socket = server.accept()) {
      socket.getInputStream().readAllBytes();
    } catch (IOException _) {
      // El cliente cierra al agotar su tiempo: es lo esperado.
    }
  }

  private static String readCommand(DataInputStream in) throws IOException {
    var command = new ByteArrayOutputStream();
    int next = in.read();
    while (next > 0) {
      command.write(next);
      next = in.read();
    }
    return command.toString(StandardCharsets.US_ASCII);
  }

  private static byte[] readChunks(DataInputStream in) throws IOException {
    var payload = new ByteArrayOutputStream();
    int length = in.readInt();
    while (length > 0) {
      payload.write(in.readNBytes(length));
      length = in.readInt();
    }
    return payload.toByteArray();
  }
}
