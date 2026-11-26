package com.usac.jobseeker.matching.infrastructure.adapter.out.cv;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.GetVisibleAvailabilityQuery;
import com.usac.jobseeker.cv.application.usecase.getvisibleavailability.GetVisibleAvailabilityUseCase;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.AvailabilityPort;
import com.usac.jobseeker.matching.domain.model.Availability;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `cv` por la disponibilidad que un reclutador puede ver, con su caso de uso público
 * (ARCH-04): `cv` aplica la lista de exclusión (BR-CAND-05, BR-VIS-06) y devuelve `UNSET` a una
 * cuenta excluida, así que aquí no hay forma de ver el estado real de quien lo ocultó. Se registra
 * a sí mismo como componente porque es el único lugar donde `matching` nombra este tipo de `cv`.
 */
@Component
public class CvVisibleAvailability implements AvailabilityPort {

  private final GetVisibleAvailabilityUseCase visible;

  public CvVisibleAvailability(GetVisibleAvailabilityUseCase visible) {
    this.visible = visible;
  }

  // El nombre del enumerado es el contrato entre módulos; un valor desconocido es UNSET.
  @Override
  public Availability visibleTo(AccountId viewer, AccountId candidate) {
    return Availability.of(visible.execute(new GetVisibleAvailabilityQuery(candidate, viewer)));
  }
}
