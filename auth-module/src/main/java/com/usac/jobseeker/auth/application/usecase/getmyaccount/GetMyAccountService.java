package com.usac.jobseeker.auth.application.usecase.getmyaccount;

import com.usac.jobseeker.auth.domain.model.Account;
import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Se registra como bean en `infrastructure.config`, sin estereotipos aquí (ADR-0015, ARCH-02).
 *
 * <p>Es el caso de uso plantilla del paso 5 del Sprint 0: muestra dónde van la métrica de negocio y
 * el log estructurado. Ninguno lleva el identificador de la cuenta: es un dato de usuario que no
 * debe acabar en un registro ni disparar la cardinalidad de una métrica (BR-DATA-01).
 */
public class GetMyAccountService implements GetMyAccountUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(GetMyAccountService.class);
  private static final String USE_CASE = "get_my_account";

  private final MyAccountPort accounts;
  private final UseCaseMetricsPort metrics;

  public GetMyAccountService(MyAccountPort accounts, UseCaseMetricsPort metrics) {
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MyAccountView> execute(GetMyAccountQuery query) {
    Optional<MyAccountView> view =
        accounts.findById(query.accountId()).map(GetMyAccountService::view);
    String outcome = view.isPresent() ? "found" : "not_found";
    metrics.record(USE_CASE, outcome);
    LOG.info("event=account_viewed outcome={} Consulta de la propia cuenta", outcome);
    return view;
  }

  private static MyAccountView view(Account account) {
    return new MyAccountView(account.id(), account.capabilities());
  }
}
