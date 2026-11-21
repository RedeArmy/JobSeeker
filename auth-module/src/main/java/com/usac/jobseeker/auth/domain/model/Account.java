package com.usac.jobseeker.auth.domain.model;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Actor;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Objects;
import java.util.Set;

/**
 * Espejo local de la identidad de Clavaris (data-model.md §4.1). No guarda correo, contraseña ni
 * sesiones: pertenecen a Clavaris.
 */
public record Account(AccountId id, Set<Capability> capabilities, DeletionStatus deletionStatus) {

  public Account {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(deletionStatus, "deletionStatus");
    capabilities = Set.copyOf(capabilities);
    // BR-ACCT-01: ninguna cuenta existe sin al menos una capacidad (CHECK ck_accounts_capability).
    if (capabilities.isEmpty()) {
      throw new IllegalArgumentException("una cuenta necesita al menos una capacidad");
    }
  }

  /** Capacidad con la que nace toda cuenta (BR-ACCT-09); la de reclutador se solicita después. */
  public static Set<Capability> initialCapabilities() {
    return Set.of(Capability.CANDIDATE);
  }

  /** Una cuenta en eliminación o ya anonimizada no actúa (ADR-0020, resolución de identidad). */
  public boolean canAct() {
    return deletionStatus == DeletionStatus.ACTIVE;
  }

  public Actor toActor() {
    return new Actor(id, capabilities);
  }
}
