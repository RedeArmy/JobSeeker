package com.usac.jobseeker.auth.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.auth.application.usecase.changerecruiterverification.RecruiterVerificationStorePort;
import com.usac.jobseeker.auth.application.usecase.getrecruiterverification.RecruiterVerificationReadPort;
import com.usac.jobseeker.common.application.account.AccountChangeObserver;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `accounts.recruiter_verification`. La lectura exige una cuenta activa y con la
 * capacidad de reclutador: una en eliminación, anonimizada o solo candidata no tiene verificación
 * que consultar. La escritura es condicional al valor leído, el mismo bloqueo optimista que usan el
 * resto de escrituras.
 */
public class JdbcRecruiterVerificationRepository
    implements RecruiterVerificationReadPort, RecruiterVerificationStorePort {

  private static final String ID = "id";

  // Dos consultas completas y no un fragmento concatenado: cada una es una constante que se lee de
  // una vez, y ninguna parte de la sentencia se arma en tiempo de ejecución.
  private static final String SELECT_ANY =
      "select recruiter_verification from accounts where id = :id and is_recruiter_enabled";

  private static final String SELECT_ACTIVE =
      "select recruiter_verification from accounts where id = :id and is_recruiter_enabled"
          + " and deletion_status = 'ACTIVE' and identity_status <> 'DELETED'";

  private final JdbcClient jdbc;
  private final AccountChangeObserver observer;

  public JdbcRecruiterVerificationRepository(JdbcClient jdbc, AccountChangeObserver observer) {
    this.jdbc = jdbc;
    this.observer = observer;
  }

  @Override
  public Optional<RecruiterVerification> findOfActiveRecruiter(AccountId accountId) {
    return read(accountId, SELECT_ACTIVE);
  }

  @Override
  public Optional<RecruiterVerification> findOfRecruiter(AccountId accountId) {
    return read(accountId, SELECT_ANY);
  }

  private Optional<RecruiterVerification> read(AccountId accountId, String sql) {
    return jdbc.sql(sql)
        .param(ID, accountId.value())
        .query((rows, n) -> RecruiterVerification.valueOf(rows.getString(1)))
        .optional();
  }

  @Override
  public boolean updateIfCurrent(
      AccountId accountId, RecruiterVerification expected, RecruiterVerification target) {
    boolean updated =
        jdbc.sql(
                    "update accounts set recruiter_verification = :target"
                        + " where id = :id and recruiter_verification = :expected"
                        + " and is_recruiter_enabled")
                .param(ID, accountId.value())
                .param("expected", expected.name())
                .param("target", target.name())
                .update()
            == 1;
    // Todo escritor de `accounts` avisa, aunque esta columna no la lea la resolución de actor: así
    // la
    // regla no tiene excepciones que recordar y olvidar de más solo cuesta una consulta.
    if (updated) {
      observer.accountChanged(accountId);
    }
    return updated;
  }
}
