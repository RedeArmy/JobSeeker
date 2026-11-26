package com.usac.jobseeker.app.infrastructure.storage;

import com.usac.jobseeker.common.application.storage.MalwareScanPort;
import com.usac.jobseeker.common.domain.error.UpstreamUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Cliente mínimo de `clamd` con el protocolo `INSTREAM` por TCP (ADR-0021 §2 y §8): `zINSTREAM\0`,
 * trozos precedidos de su longitud en 4 bytes y un trozo de longitud 0 al final. Validado contra
 * ClamAV real en el spike V3 (`13-spike-almacenamiento-s3-y-clamav.md`).
 *
 * <p>Solo `stream: OK` es limpio y solo `… FOUND` es infectado. Cualquier otra respuesta, un error
 * de red o un tiempo agotado es «antivirus no disponible», nunca «limpio»: ante la duda no se
 * guarda (*fail-closed*, §7).
 */
final class ClamAvScanner implements MalwareScanPort {

  private static final String DEPENDENCY = "malware_scan";
  private static final byte[] INSTREAM_COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] PING_COMMAND = "zPING\0".getBytes(StandardCharsets.US_ASCII);
  private static final String CLEAN = "stream: OK";
  private static final String FOUND = " FOUND";
  private static final String STREAM_PREFIX = "stream: ";
  // Una respuesta de clamd ocupa unas decenas de bytes; más es un servicio que no es clamd.
  private static final int MAX_REPLY = 1024;

  private final MalwareScanProperties properties;
  private final Timer cleanTimer;
  private final Timer infectedTimer;
  private final Timer unavailableTimer;

  ClamAvScanner(MalwareScanProperties properties, MeterRegistry meters) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.cleanTimer = timer(meters, "clean");
    this.infectedTimer = timer(meters, "infected");
    this.unavailableTimer = timer(meters, "unavailable");
  }

  @Override
  public ScanVerdict scan(byte[] content) {
    long start = System.nanoTime();
    try {
      ScanVerdict verdict = verdictOf(exchange(content));
      (verdict instanceof ScanVerdict.Clean ? cleanTimer : infectedTimer)
          .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      return verdict;
    } catch (IOException e) {
      unavailableTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      throw new UpstreamUnavailableException(DEPENDENCY, e);
    } catch (UpstreamUnavailableException e) {
      unavailableTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      throw e;
    }
  }

  /** `PING` → `PONG`: lo usa el indicador de salud sin enviar ningún archivo. */
  @SuppressWarnings("PMD.CloseResource") // los flujos son del socket, que se cierra con él
  void ping() {
    try (Socket socket = connect()) {
      OutputStream out = socket.getOutputStream();
      out.write(PING_COMMAND);
      out.flush();
      String reply = read(socket.getInputStream());
      if (!"PONG".equals(reply)) {
        throw new UpstreamUnavailableException(DEPENDENCY, new IOException("respuesta inesperada"));
      }
    } catch (IOException e) {
      throw new UpstreamUnavailableException(DEPENDENCY, e);
    }
  }

  @SuppressWarnings("PMD.CloseResource") // los flujos son del socket, que se cierra con él
  private String exchange(byte[] content) throws IOException {
    try (Socket socket = connect()) {
      OutputStream out = socket.getOutputStream();
      out.write(INSTREAM_COMMAND);
      int chunk = properties.chunkSize();
      for (int offset = 0; offset < content.length; offset += chunk) {
        int length = Math.min(chunk, content.length - offset);
        out.write(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        out.write(content, offset, length);
      }
      out.write(new byte[Integer.BYTES]);
      out.flush();
      return read(socket.getInputStream());
    }
  }

  private Socket connect() throws IOException {
    Socket socket = new Socket();
    try {
      socket.connect(
          new InetSocketAddress(properties.host(), properties.port()),
          Math.toIntExact(properties.connectTimeout().toMillis()));
      socket.setSoTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
      return socket;
    } catch (IOException e) {
      socket.close();
      throw e;
    }
  }

  // clamd termina la respuesta con el carácter nulo del modo `z`.
  private static String read(InputStream in) throws IOException {
    ByteArrayOutputStream reply = new ByteArrayOutputStream();
    int next = in.read();
    while (next != -1 && next != 0) {
      if (reply.size() >= MAX_REPLY) {
        throw new IOException("respuesta de clamd demasiado larga");
      }
      reply.write(next);
      next = in.read();
    }
    return reply.toString(StandardCharsets.US_ASCII).strip();
  }

  static ScanVerdict verdictOf(String reply) {
    if (CLEAN.equals(reply)) {
      return new ScanVerdict.Clean();
    }
    if (reply.length() > STREAM_PREFIX.length() + FOUND.length()
        && reply.startsWith(STREAM_PREFIX)
        && reply.endsWith(FOUND)) {
      String signature =
          reply.substring(STREAM_PREFIX.length(), reply.length() - FOUND.length()).strip();
      if (!signature.isEmpty()) {
        return new ScanVerdict.Infected(signature);
      }
    }
    throw new UpstreamUnavailableException(
        DEPENDENCY, new IOException("respuesta de clamd no reconocida"));
  }

  private static Timer timer(MeterRegistry meters, String outcome) {
    return Timer.builder("jobseeker.malware.scan")
        .description("Duración del escaneo antivirus de un archivo subido (ADR-0021)")
        .tag("outcome", outcome)
        .register(meters);
  }
}
