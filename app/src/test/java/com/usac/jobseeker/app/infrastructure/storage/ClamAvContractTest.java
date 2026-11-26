package com.usac.jobseeker.app.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.application.storage.MalwareScanPort.ScanVerdict;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * El cliente contra ClamAV real con el archivo de prueba EICAR (ADR-0021 §8). No corre en cada
 * push: la imagen ocupa 1 a 2 GB de memoria (`13-spike-almacenamiento-s3-y-clamav.md` §3). Se
 * ejecuta con `-Dclamav.contract=true`, a mano o en un flujo aparte.
 */
@EnabledIfSystemProperty(named = "clamav.contract", matches = "true")
class ClamAvContractTest {

  // Cadena estándar de prueba de antivirus: inofensiva, pero todo antivirus la detecta.
  private static final String EICAR =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

  @Test
  void eicarIsInfectedAndAnOrdinaryFileIsClean() {
    try (GenericContainer<?> clamav =
        new GenericContainer<>(DockerImageName.parse("clamav/clamav:1.5.5"))
            .withExposedPorts(3310)
            .waitingFor(Wait.forLogMessage(".*socket found, clamd started.*", 1))
            .withStartupTimeout(Duration.ofMinutes(5))) {
      clamav.start();
      var scanner =
          new ClamAvScanner(
              new MalwareScanProperties(
                  clamav.getHost(),
                  clamav.getMappedPort(3310),
                  Duration.ofSeconds(2),
                  Duration.ofSeconds(30),
                  64 * 1024),
              new SimpleMeterRegistry());

      scanner.ping();
      var infected = scanner.scan(EICAR.getBytes(StandardCharsets.US_ASCII));
      var clean = scanner.scan("%PDF-1.7\nnada raro\n%%EOF".getBytes(StandardCharsets.US_ASCII));

      assertThat(infected).isInstanceOf(ScanVerdict.Infected.class);
      assertThat(clean).isInstanceOf(ScanVerdict.Clean.class);
    }
  }
}
