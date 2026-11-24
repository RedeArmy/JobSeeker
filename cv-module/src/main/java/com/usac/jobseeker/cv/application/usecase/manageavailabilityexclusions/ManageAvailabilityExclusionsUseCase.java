package com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions;

import com.usac.jobseeker.common.domain.error.AlreadyExistsException;
import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.ValidationFailedException;
import java.util.List;

/**
 * La lista de exclusión de disponibilidad del propio candidato (BR-CAND-05): listar, añadir y
 * quitar, cuenta por cuenta. Todas lanzan {@link ForbiddenCapabilityException} sin la capacidad de
 * candidato y {@link NotFoundException} si aún no hay perfil de candidato.
 */
public interface ManageAvailabilityExclusionsUseCase {

  List<ExclusionView> list(ListAvailabilityExclusionsQuery query);

  /**
   * @throws ValidationFailedException si la cuenta es la propia o la lista llegó a su tope
   * @throws NotFoundException si la cuenta a excluir no existe
   * @throws AlreadyExistsException si ya estaba en la lista
   */
  List<ExclusionView> add(AddAvailabilityExclusionCommand command);

  /**
   * @throws NotFoundException si esa cuenta no estaba en la lista
   */
  List<ExclusionView> remove(RemoveAvailabilityExclusionCommand command);
}
