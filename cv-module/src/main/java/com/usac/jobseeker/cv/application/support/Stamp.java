package com.usac.jobseeker.cv.application.support;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * El reloj y el generador de identificadores de un caso de uso, juntos: son lo no determinista que
 * las pruebas fijan, y agruparlos mantiene corto el constructor de los servicios.
 */
public record Stamp(Clock clock, Supplier<UUID> ids) {

  public Stamp {
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(ids, "ids");
  }

  public static Stamp system() {
    return new Stamp(Clock.systemUTC(), UUID::randomUUID);
  }
}
