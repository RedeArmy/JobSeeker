package com.usac.jobseeker.cv.infrastructure.adapter.out.auth;

import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.cv.application.usecase.manageavailabilityexclusions.AccountDirectoryPort;
import org.springframework.stereotype.Component;

/**
 * Pregunta a `auth` si una cuenta sigue viva con su caso de uso público: `cv` nunca lee `accounts`
 * (ARCH-04). Una cuenta en periodo de gracia de borrado cuenta como viva porque el titular puede
 * cancelarlo al volver a iniciar sesión (BR-DATA-04); una anonimizada, sin identidad o nunca
 * aprovisionada no vuelve y se trata como inexistente. Se registra como componente por la misma
 * razón que {@link AuthCandidateCapability}.
 */
@Component
public class AuthAccountDirectory implements AccountDirectoryPort {

  private final ResolveActorUseCase resolveActor;

  public AuthAccountDirectory(ResolveActorUseCase resolveActor) {
    this.resolveActor = resolveActor;
  }

  @Override
  public boolean isLive(AccountId accountId) {
    return switch (resolveActor.execute(new ResolveActorQuery(accountId))) {
      case ActorResolution.Resolved _ -> true;
      case ActorResolution.Rejected(var reason) ->
          reason == ActorResolution.Reason.PENDING_DELETION;
    };
  }
}
