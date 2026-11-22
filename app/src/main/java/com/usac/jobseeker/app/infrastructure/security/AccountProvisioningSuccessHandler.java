package com.usac.jobseeker.app.infrastructure.security;

import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountCommand;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountResult;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

/**
 * Al terminar el login OIDC crea la cuenta local si es la primera vez (ADR-0020, BR-ACCT-09) y
 * continúa hacia la página que el usuario pedía, o `/app/home`.
 */
final class AccountProvisioningSuccessHandler
    extends SavedRequestAwareAuthenticationSuccessHandler {

  private static final Logger LOG =
      LoggerFactory.getLogger(AccountProvisioningSuccessHandler.class);

  private final ProvisionAccountUseCase provisionAccount;

  AccountProvisioningSuccessHandler(ProvisionAccountUseCase provisionAccount) {
    this.provisionAccount = provisionAccount;
    setDefaultTargetUrl("/app/home");
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    AccountId accountId;
    try {
      accountId = AccountId.fromSubject(authentication.getName());
    } catch (IllegalArgumentException _) {
      // Un `sub` que no es un UUID no puede ser de Clavaris: se corta la sesión recién creada.
      LOG.warn("event=login_rejected reason=subject_no_uuid");
      request.getSession().invalidate();
      ErrorEnvelope.write(
          request, response, 403, "IDENTITY_INVALID", "La identidad recibida no es válida.");
      return;
    }
    ProvisionAccountResult result =
        provisionAccount.execute(new ProvisionAccountCommand(accountId));
    LOG.info("event=login_succeeded accountId={} accountCreated={}", accountId, result.created());
    super.onAuthenticationSuccess(request, response, authentication);
  }
}
