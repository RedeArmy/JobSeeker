package com.usac.jobseeker.common.domain.model;

import java.util.Set;

/**
 * Quién actúa en una petición. Se reconstruye en el servidor desde la base local en cada petición y
 * nunca de claims del token (ADR-0020, NFR-SEC-03): cada caso de uso exige explícitamente la
 * capacidad que necesita (BR-ACCT-04).
 */
public record Actor(AccountId id, Set<Capability> capabilities) {

  public Actor {
    capabilities = Set.copyOf(capabilities);
  }

  public boolean has(Capability capability) {
    return capabilities.contains(capability);
  }
}
