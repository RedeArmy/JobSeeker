package com.usac.jobseeker.app.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.app.InfrastructureTestConfiguration;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountCommand;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountResult;
import com.usac.jobseeker.auth.application.usecase.provisionaccount.ProvisionAccountUseCase;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ActorResolution.Reason;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorQuery;
import com.usac.jobseeker.auth.application.usecase.resolveactor.ResolveActorUseCase;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.Capability;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Los casos de uso de `auth` contra la tabla real `accounts` de Postgres 17 (ADR-0020, BR-ACCT-09).
 */
@SpringBootTest
@Import(InfrastructureTestConfiguration.class)
class AccountPersistenceIntegrationTest {

  @Autowired private ProvisionAccountUseCase provision;
  @Autowired private ResolveActorUseCase resolve;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void theFirstLoginStoresACandidateOnlyAccount() {
    AccountId id = new AccountId(UUID.randomUUID());

    ProvisionAccountResult result = provision.execute(new ProvisionAccountCommand(id));

    assertThat(result.created()).isTrue();
    assertThat(
            jdbc.queryForMap(
                "select is_candidate_enabled c, is_recruiter_enabled r, deletion_status s"
                    + " from accounts where id = ?",
                id.value()))
        .containsEntry("c", true)
        .containsEntry("r", false)
        .containsEntry("s", "ACTIVE");
  }

  @Test
  void aSecondLoginDoesNotOverwriteCapabilitiesGrantedLater() {
    AccountId id = new AccountId(UUID.randomUUID());
    provision.execute(new ProvisionAccountCommand(id));
    jdbc.update("update accounts set is_recruiter_enabled = true where id = ?", id.value());

    ProvisionAccountResult result = provision.execute(new ProvisionAccountCommand(id));

    assertThat(result.created()).isFalse();
    assertThat(result.actor().capabilities())
        .containsExactlyInAnyOrder(Capability.CANDIDATE, Capability.RECRUITER);
  }

  @Test
  void manySimultaneousFirstLoginsCreateExactlyOneAccountAndNoneFails() throws Exception {
    AccountId id = new AccountId(UUID.randomUUID());
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<ProvisionAccountResult>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<ProvisionAccountResult> login =
            () -> {
              start.await();
              return provision.execute(new ProvisionAccountCommand(id));
            };
        futures.add(pool.submit(login));
      }
      start.countDown();
      long created = 0;
      for (Future<ProvisionAccountResult> future : futures) {
        // Ninguna falla: el segundo login simultáneo no choca con la clave primaria.
        if (future.get().created()) {
          created++;
        }
      }
      assertThat(created).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from accounts where id = ?", Integer.class, id.value()))
        .isEqualTo(1);
  }

  @Test
  void theActorIsRebuiltFromTheDatabaseAndAStateChangeIsSeenOnTheNextRequest() {
    AccountId id = new AccountId(UUID.randomUUID());
    provision.execute(new ProvisionAccountCommand(id));

    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isInstanceOf(ActorResolution.Resolved.class);

    jdbc.update(
        "update accounts set deletion_status = 'PENDING_DELETION' where id = ?", id.value());
    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isEqualTo(new ActorResolution.Rejected(Reason.PENDING_DELETION));

    jdbc.update("update accounts set deletion_status = 'ANONYMIZED' where id = ?", id.value());
    assertThat(resolve.execute(new ResolveActorQuery(id)))
        .isEqualTo(new ActorResolution.Rejected(Reason.ANONYMIZED));
  }

  @Test
  void anUnknownIdentityIsNotProvisioned() {
    assertThat(resolve.execute(new ResolveActorQuery(new AccountId(UUID.randomUUID()))))
        .isEqualTo(new ActorResolution.Rejected(Reason.NOT_PROVISIONED));
  }
}
