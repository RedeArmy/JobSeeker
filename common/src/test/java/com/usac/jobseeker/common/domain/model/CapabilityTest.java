package com.usac.jobseeker.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Los nombres de las capacidades son parte del contrato: se leen de la base y de la API. Renombrar
 * uno es un cambio de contrato y debe romper esta prueba a propósito.
 */
class CapabilityTest {

  @Test
  void theCapabilitiesAreExactlyCandidateAndRecruiterByName() {
    assertThat(Arrays.stream(Capability.values()).map(Enum::name))
        .containsExactlyInAnyOrder("CANDIDATE", "RECRUITER");
  }
}
