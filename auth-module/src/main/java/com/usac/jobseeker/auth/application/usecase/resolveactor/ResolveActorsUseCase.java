package com.usac.jobseeker.auth.application.usecase.resolveactor;

import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Map;

/**
 * La misma resolución que {@link ResolveActorUseCase}, para varias cuentas con **una** consulta:
 * quien debe comprobar la capacidad de cientos de cuentas (los sugeridos puntúan hasta 200) no
 * puede pagar una ida y vuelta por cuenta (TD-039). Es una interfaz aparte para no obligar a quien
 * implementa o simula la resolución individual a cambiar. Una cuenta que no existe responde
 * `NOT_PROVISIONED`, igual que en la individual.
 */
public interface ResolveActorsUseCase {

  Map<AccountId, ActorResolution> execute(ResolveActorsQuery query);
}
