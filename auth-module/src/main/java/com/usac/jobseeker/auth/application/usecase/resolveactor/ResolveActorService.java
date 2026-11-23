package com.usac.jobseeker.auth.application.usecase.resolveactor;

import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.auth.domain.model.IdentityStatus;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02). */
public class ResolveActorService implements ResolveActorUseCase {

  private final ActorLookupPort accounts;

  public ResolveActorService(ActorLookupPort accounts) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
  }

  @Override
  @Transactional(readOnly = true)
  public ActorResolution execute(ResolveActorQuery query) {
    return accounts
        .findById(query.accountId())
        .map(ResolveActorService::resolve)
        .orElseGet(() -> new ActorResolution.Rejected(ActorResolution.Reason.NOT_PROVISIONED));
  }

  private static ActorResolution resolve(Account account) {
    // Una identidad borrada en Clavaris no vuelve (terminal), así que no puede quedar obsoleta como
    // una suspensión a la que se le perdió la reactivación: es la única que cierra el paso.
    if (account.identityStatus() == IdentityStatus.DELETED) {
      return new ActorResolution.Rejected(ActorResolution.Reason.IDENTITY_DELETED);
    }
    return switch (account.deletionStatus()) {
      case ACTIVE -> new ActorResolution.Resolved(account.toActor());
      case PENDING_DELETION ->
          new ActorResolution.Rejected(ActorResolution.Reason.PENDING_DELETION);
      case ANONYMIZED -> new ActorResolution.Rejected(ActorResolution.Reason.ANONYMIZED);
    };
  }
}
