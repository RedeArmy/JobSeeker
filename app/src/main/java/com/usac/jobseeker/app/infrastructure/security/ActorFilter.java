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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reconstruye el `Actor` desde la base local en cada petición (ADR-0020): las capacidades salen de
 * `accounts`, nunca de claims del token (NFR-SEC-03, BR-ACCT-04). Una cuenta en eliminación o
 * anonimizada no actúa (BR-DATA-04/05); salir de la sesión sí está permitido en cualquier estado.
 */
final class ActorFilter extends OncePerRequestFilter {

  private static final String LOGOUT_PATH = "/app/logout";

  private final ResolveActorUseCase resolveActor;
  private final ProvisionAccountUseCase provisionAccount;

  ActorFilter(ResolveActorUseCase resolveActor, ProvisionAccountUseCase provisionAccount) {
    this.resolveActor = resolveActor;
    this.provisionAccount = provisionAccount;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof OAuth2AuthenticationToken) || isLogout(request)) {
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

  private static boolean isLogout(HttpServletRequest request) {
    return LOGOUT_PATH.equals(request.getRequestURI());
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
