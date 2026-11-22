package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountCommand;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reconstruye el `Actor` desde la base local en cada petición (ADR-0020): las capacidades salen de
 * `accounts`, nunca de claims del token (NFR-SEC-03, BR-ACCT-04). Una cuenta en eliminación o
 * anonimizada no actúa (BR-DATA-04/05); salir de la sesión sí está permitido en cualquier estado.
 */
final class ActorFilter extends OncePerRequestFilter {

  private final ResolveActorUseCase resolveActor;
  private final ProvisionAccountUseCase provisionAccount;
  private final Predicate<Authentication> appliesTo;
  private final Set<String> alwaysAllowedPaths;

  /**
   * @param appliesTo qué autenticaciones resuelven un actor: la sesión OIDC en la web y el Bearer
   *     en la API; cualquier otra petición sigue su camino sin actor
   * @param alwaysAllowedPaths rutas que se permiten en cualquier estado de la cuenta (salir)
   */
  ActorFilter(
      ResolveActorUseCase resolveActor,
      ProvisionAccountUseCase provisionAccount,
      Predicate<Authentication> appliesTo,
      Set<String> alwaysAllowedPaths) {
    this.resolveActor = resolveActor;
    this.provisionAccount = provisionAccount;
    this.appliesTo = appliesTo;
    this.alwaysAllowedPaths = Set.copyOf(alwaysAllowedPaths);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !appliesTo.test(authentication)
        || alwaysAllowedPaths.contains(request.getRequestURI())) {
      chain.doFilter(request, response);
      return;
    }
    AccountId accountId;
    try {
      accountId = AccountId.fromSubject(authentication.getName());
    } catch (IllegalArgumentException _) {
      ErrorEnvelope.write(
          request, response, 403, "IDENTITY_INVALID", "La identidad de la sesión no es válida.");
      return;
    }
    ActorResolution resolution = resolveActor.execute(new ResolveActorQuery(accountId));
    if (resolution instanceof ActorResolution.Rejected(var reason)
        && reason == ActorResolution.Reason.NOT_PROVISIONED) {
      // Sesión anterior al aprovisionamiento: se crea la cuenta de forma perezosa (BR-ACCT-09).
      provisionAccount.execute(new ProvisionAccountCommand(accountId));
      resolution = resolveActor.execute(new ResolveActorQuery(accountId));
    }
    switch (resolution) {
      case ActorResolution.Resolved(var actor) -> {
        request.setAttribute(CurrentActor.ATTRIBUTE, actor);
        chain.doFilter(request, response);
      }
      case ActorResolution.Rejected(var reason) -> reject(request, response, reason);
    }
  }

  private static void reject(
      HttpServletRequest request, HttpServletResponse response, ActorResolution.Reason reason)
      throws IOException {
    switch (reason) {
      case PENDING_DELETION ->
          ErrorEnvelope.write(
              request,
              response,
              403,
              "ACCOUNT_PENDING_DELETION",
              "La cuenta está en periodo de eliminación.");
      case ANONYMIZED, NOT_PROVISIONED ->
          ErrorEnvelope.write(
              request, response, 403, "ACCOUNT_UNAVAILABLE", "La cuenta no está disponible.");
    }
  }
}
