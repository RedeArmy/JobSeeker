package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import java.util.Map;
import java.util.Optional;

/**
 * Evita preguntar a la base de datos por la misma cuenta varias veces en una misma petición: el
 * `ActorFilter` la resuelve y después cada caso de uso de `cv` vuelve a preguntar por su capacidad
 * (6 consultas a `accounts` en una lectura del perfil de candidato y 12 en un reemplazo de
 * conjunto).
 *
 * <p>Lo recordado vive en el {@link ActorResolutionScope} que abre el `ActorFilter`, nunca entre
 * peticiones: así la comprobación de vida de ADR-0020 sigue siendo fresca en cada una. Reglas que
 * lo hacen seguro:
 *
 * <ul>
 *   <li>Solo se recuerda una cuenta resuelta, y es una decisión, no un descuido. Recordar un
 *       rechazo no ahorra nada: si es el de quien llama, el `ActorFilter` responde 403 y la
 *       petición termina sin que nadie vuelva a preguntar. Y recordar «sin aprovisionar» es
 *       peligroso: dos primeros accesos simultáneos de la misma cuenta compiten por crearla, el que
 *       pierde la carrera no escribe (`insertIfAbsent` devuelve falso, nadie avisa) y su segunda
 *       pregunta debe ver la cuenta que creó el otro, no el rechazo de antes.
 *   <li>Sin ámbito abierto no se recuerda nada: pruebas, sondeador y lotes van siempre a la base.
 *   <li>Toda escritura de `accounts` avisa a {@link ActorResolutionScope#forget} desde el adaptador
 *       que escribe ({@code AccountChangeObserver}), así que un caso de uso que cambie la cuenta no
 *       tiene que acordarse de nada y los siguientes de la misma petición la ven cambiada.
 * </ul>
 *
 * <p>Lo único que no ve es lo que otra petición escriba mientras esta corre: como antes, la cuenta
 * se comprueba al empezar la petición, no durante ella.
 */
public class RequestScopedActorResolution implements ResolveActorUseCase {

  private final ResolveActorUseCase delegate;

  public RequestScopedActorResolution(ResolveActorUseCase delegate) {
    this.delegate = delegate;
  }

  @Override
  public ActorResolution execute(ResolveActorQuery query) {
    Optional<Map<AccountId, Object>> open = ActorResolutionScope.current();
    if (open.isEmpty()) {
      return delegate.execute(query);
    }
    Map<AccountId, Object> scope = open.get();
    Object known = scope.get(query.accountId());
    if (known instanceof ActorResolution cached) {
      return cached;
    }
    ActorResolution fresh = delegate.execute(query);
    if (fresh instanceof ActorResolution.Resolved) {
      scope.put(query.accountId(), fresh);
    }
    return fresh;
  }
}
