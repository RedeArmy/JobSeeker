package com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.error.ForbiddenCapabilityException;
import com.usac.jobseeker.common.domain.error.InvalidStateException;
import com.usac.jobseeker.common.domain.error.NotFoundException;
import com.usac.jobseeker.common.domain.error.SuggestionsDeniedException;
import com.usac.jobseeker.common.domain.error.SuggestionsDeniedException.Reason;
import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.common.domain.model.RecruiterVerification;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingStorePort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.OwnedJobPort.OwnedJob;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El orden de los controles es parte del diseño (amenaza T-N1): la verificación va antes de tocar
 * la vacante, y la cuota se aplica al final, bajo candado y registrando lo que se muestra.
 */
class SuggestCandidatesForJobServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
  private static final String HASH = "a".repeat(64);

  private final AccountId recruiter = new AccountId(UUID.randomUUID());
  private final JobPostingId job = new JobPostingId(UUID.randomUUID());
  private final List<CandidateProfileId> pool = new ArrayList<>();
  private final List<String> calls = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final List<List<CandidateProfileId>> recorded = new ArrayList<>();
  private final Set<CandidateProfileId> viewed = new HashSet<>();
  private Optional<RecruiterVerification> verification =
      Optional.of(RecruiterVerification.VERIFIED);
  private Optional<OwnedJob> owned = Optional.of(new OwnedJob(true, false));
  private Optional<EntityEmbedding> embedding =
      Optional.of(new EntityEmbedding(EmbeddingVector.of(1f, 0f), "modelo-a", HASH, false, NOW));
  private int retrievedLimit;
  private String retrievedModel;
  private Instant viewedSince;

  private final EmbeddingStorePort store =
      (EmbeddingStorePort)
          Proxy.newProxyInstance(
              EmbeddingStorePort.class.getClassLoader(),
              new Class<?>[] {EmbeddingStorePort.class},
              (proxy, method, args) -> {
                if (method.getName().equals("findJobEmbedding")) {
                  calls.add("embedding");
                  return embedding;
                }
                throw new UnsupportedOperationException(method.getName());
              });

  private final SuggestCandidatesForJobService service =
      new SuggestCandidatesForJobService(
          account -> {
            calls.add("trust");
            return verification;
          },
          (account, id) -> {
            calls.add("owned");
            return owned;
          },
          store,
          (vector, model, limit) -> {
            calls.add("retrieval");
            retrievedLimit = limit;
            retrievedModel = model;
            return pool.stream().limit(limit).map(c -> new NearestProfile(c, 0.5)).toList();
          },
          new ProfileViewLogPort() {
            public void lock(AccountId viewer) {
              calls.add("lock");
            }

            public Set<CandidateProfileId> viewedSince(AccountId viewer, Instant since) {
              calls.add("viewed");
              viewedSince = since;
              return viewed;
            }

            public void record(
                AccountId viewer, JobPostingId job, List<CandidateProfileId> shown, Instant at) {
              calls.add("record");
              recorded.add(shown);
            }
          },
          new SuggestionsPolicy(5, Duration.ofHours(24), 10),
          (useCase, outcome) -> metrics.add(outcome),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private List<CandidateProfileId> candidates(int count) {
    for (int i = 0; i < count; i++) {
      pool.add(new CandidateProfileId(UUID.randomUUID()));
    }
    return List.copyOf(pool);
  }

  private SuggestionsResult ask(int size) {
    return service.execute(new SuggestCandidatesForJobQuery(recruiter, job, size));
  }

  // ---- quién puede ----

  @Test
  void aVerifiedOwnerGetsTheCandidatesInRankingOrderAndTheyAreRecorded() {
    var ranked = candidates(3);

    var result = ask(10);

    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.OK);
    assertThat(result.candidates()).containsExactlyElementsOf(ranked);
    assertThat(result.remainingQuota()).isEqualTo(2);
    assertThat(recorded).containsExactly(ranked);
    assertThat(metrics).containsExactly("ok");
  }

  @Test
  void anAccountThatIsNotARecruiterIsForbiddenAndNothingElseIsConsulted() {
    verification = Optional.empty();

    assertThatThrownBy(() -> ask(5)).isInstanceOf(ForbiddenCapabilityException.class);

    assertThat(calls).containsExactly("trust");
    assertThat(metrics).containsExactly("forbidden");
  }

  // El control central contra el raspado (T-N1): sin verificar no se ve nada, ni se toca la
  // vacante.
  @Test
  void anUnverifiedRecruiterIsDeniedBeforeTheJobIsEvenLookedUp() {
    verification = Optional.of(RecruiterVerification.UNVERIFIED);
    candidates(3);

    assertThatThrownBy(() -> ask(5))
        .isInstanceOfSatisfying(
            SuggestionsDeniedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.NOT_VERIFIED));

    assertThat(calls).containsExactly("trust");
    assertThat(recorded).isEmpty();
    assertThat(metrics).containsExactly("not_verified");
  }

  @Test
  void aSuspendedRecruiterIsDeniedWithItsOwnReason() {
    verification = Optional.of(RecruiterVerification.SUSPENDED);

    assertThatThrownBy(() -> ask(5))
        .isInstanceOfSatisfying(
            SuggestionsDeniedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.SUSPENDED));

    assertThat(calls).containsExactly("trust");
    assertThat(metrics).containsExactly("suspended");
  }

  // BR-VIS-03: la vacante de otro no existe para quien pregunta.
  @Test
  void aJobThatIsNotTheCallersDoesNotExist() {
    owned = Optional.empty();

    assertThatThrownBy(() -> ask(5)).isInstanceOf(NotFoundException.class);

    assertThat(calls).containsExactly("trust", "owned");
    assertThat(metrics).containsExactly("not_found");
  }

  @Test
  void aJobThatIsNotPublishedGeneratesNoSuggestions() {
    owned = Optional.of(new OwnedJob(false, false));

    assertThatThrownBy(() -> ask(5)).isInstanceOf(InvalidStateException.class);

    assertThat(calls).containsExactly("trust", "owned");
    assertThat(metrics).containsExactly("not_published");
  }

  // ---- estados de la vacante ----

  // BR-MATCH-08: en REVIEWING deja de generar entradas nuevas.
  @Test
  void aJobInReviewingIsPausedWithNoCandidatesAndNothingIsRecorded() {
    owned = Optional.of(new OwnedJob(true, true));
    candidates(3);

    var result = ask(5);

    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.PAUSED);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.remainingQuota()).isEqualTo(5);
    assertThat(calls).doesNotContain("retrieval", "lock", "record");
    assertThat(metrics).containsExactly("paused");
  }

  @Test
  void aJobWithoutAVectorYetIsPendingAndNothingIsRetrieved() {
    embedding = Optional.empty();
    candidates(3);

    var result = ask(5);

    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.PENDING_EMBEDDING);
    assertThat(result.candidates()).isEmpty();
    assertThat(calls).doesNotContain("retrieval", "record");
    assertThat(metrics).containsExactly("pending_embedding");
  }

  @Test
  void aPausedJobStillReportsHowMuchQuotaIsLeft() {
    owned = Optional.of(new OwnedJob(true, true));
    viewed.addAll(candidates(2));

    assertThat(ask(5).remainingQuota()).isEqualTo(3);
  }

  // ---- la cuota ----

  @Test
  void aProfileAlreadyViewedIsFreeAndNewOnesSpendTheQuota() {
    var ranked = candidates(4);
    viewed.add(ranked.get(0));
    viewed.add(ranked.get(1));

    var result = ask(10);

    assertThat(result.candidates()).containsExactlyElementsOf(ranked);
    // Quedaban 3 (5 − 2 vistos); los 2 nuevos gastan 2.
    assertThat(result.remainingQuota()).isEqualTo(1);
  }

  @Test
  void whenTheQuotaDoesNotCoverThePageTheBestRankedFitAndItIsNotAnError() {
    var ranked = candidates(8);

    var result = ask(10);

    assertThat(result.candidates()).containsExactlyElementsOf(ranked.subList(0, 5));
    assertThat(result.remainingQuota()).isZero();
    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.OK);
  }

  @Test
  void aPageOfOnlyNewProfilesWithNoQuotaLeftIsAQuotaDenialAndNothingIsRecorded() {
    candidates(3);
    for (int i = 0; i < 5; i++) {
      viewed.add(new CandidateProfileId(UUID.randomUUID()));
    }

    assertThatThrownBy(() -> ask(3))
        .isInstanceOfSatisfying(
            SuggestionsDeniedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.QUOTA_EXCEEDED));

    assertThat(recorded).isEmpty();
    assertThat(metrics).containsExactly("quota_exceeded");
  }

  // La ventana es móvil: se cuenta desde hace 24 h y no desde la medianoche.
  @Test
  void theWindowIsTheLast24HoursNotACalendarDay() {
    candidates(1);

    ask(1);

    assertThat(viewedSince).isEqualTo(NOW.minus(Duration.ofHours(24)));
  }

  // El candado va antes de leer lo visto y todo en una sola transacción, o dos pedidos simultáneos
  // contarían ambos «lo que queda».
  @Test
  void theViewerIsLockedBeforeWhatWasViewedIsReadAndBeforeAnythingIsRecorded() {
    candidates(2);

    ask(2);

    assertThat(calls)
        .containsExactly("trust", "owned", "embedding", "retrieval", "lock", "viewed", "record");
  }

  // ---- la recuperación ----

  @Test
  void theRetrievalUsesTheJobVectorModelAndThePageSizeCappedByThePolicy() {
    candidates(30);

    ask(25);

    assertThat(retrievedModel).isEqualTo("modelo-a");
    // El tope de la política es 10, aunque se pidan 25.
    assertThat(retrievedLimit).isEqualTo(10);
  }

  @Test
  void aSmallerPageIsRespected() {
    candidates(30);

    ask(3);

    assertThat(retrievedLimit).isEqualTo(3);
  }

  @Test
  void nobodyRetrievedMeansAnEmptyOkPageNotAQuotaDenial() {
    var result = ask(5);

    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.OK);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.remainingQuota()).isEqualTo(5);
  }

  // BR-DATA-01: ni la métrica lleva el identificador de la cuenta, de la vacante o de un candidato.
  @Test
  void theMetricsCarryNoIdentifiers() {
    candidates(2);

    ask(2);

    assertThat(metrics)
        .noneMatch(m -> m.contains(recruiter.toString()) || m.contains(job.toString()));
  }

  @Test
  void aQueryNeedsAPositivePageSize() {
    assertThatThrownBy(() -> new SuggestCandidatesForJobQuery(recruiter, job, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
