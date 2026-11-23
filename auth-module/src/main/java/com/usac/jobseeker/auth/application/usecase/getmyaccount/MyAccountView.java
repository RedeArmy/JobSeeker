package com.usac.jobseeker.auth.application.usecase.getmyaccount;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.Objects;
import java.util.Set;

/**
 * Lo que una cuenta puede saber de sí misma. Solo identificador y capacidades: nada de datos
 * personales, que viven en Clavaris (ADR-0020), ni del estado interno de eliminación o identidad.
 */
public record MyAccountView(AccountId id, Set<Capability> capabilities) {

  public MyAccountView {
    Objects.requireNonNull(id, "id");
    capabilities = Set.copyOf(capabilities);
  }
}
