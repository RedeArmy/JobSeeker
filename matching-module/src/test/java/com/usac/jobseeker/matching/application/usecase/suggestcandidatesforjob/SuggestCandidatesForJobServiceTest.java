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
import com.usac.jobseeker.matching.application.usecase.calculatecompatibilityscore.InMemoryScoreStore;
import com.usac.jobseeker.matching.application.usecase.embedentity.CandidateSignalsReader;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingStorePort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.OwnedJobPort.OwnedJob;
import com.usac.jobseeker.matching.domain.model.Availability;
import com.usac.jobseeker.matching.domain.model.Calibration;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import com.usac.jobseeker.matching.domain.model.ScoreReason;
import com.usac.jobseeker.matching.domain.model.Weights;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El orden de los controles es parte del diseño (amenaza T-N1): la verificación va antes de tocar
 * la vacante y la cuota al final, bajo candado. Sobre eso, las etapas 2 y 3 del ADR-0017: puntaje
 * explicable, completitud mínima, desempate por disponibilidad visible y tarjeta enmascarada.
 */
class SuggestCandidatesForJobServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
  private static final String HASH = "a".repeat(64);
  private static final String MODEL = "modelo-a";

  /**
   * Un candidato recuperado: lo que devuelve la etapa 1, sus señales vivas y lo que ve el
   * reclutador.
   */
  private record Pooled(NearestProfile nearest, CandidateSignals signals, Availability visible) {}

  private final AccountId recruiter = new AccountId(UUID.randomUUID());
  private final JobPostingId job = new JobPostingId(UUID.randomUUID());
  private final List<Pooled> pool = new ArrayList<>();
  private final Map<AccountId, Pooled> byAccount = new HashMap<>();
  private final List<String> calls = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private final List<List<CandidateProfileId>> recorded = new ArrayList<>();
  private final Set<CandidateProfileId> viewed = new HashSet<>();
  private final InMemoryScoreStore scoreStore = new InMemoryScoreStore();
  private final List<AccountId> availabilityAsked = new ArrayList<>();
  private Optional<RecruiterVerification> verification =
      Optional.of(RecruiterVerification.VERIFIED);
  private Optional<OwnedJob> owned = Optional.of(new OwnedJob(true, false));
  private Optional<EntityEmbedding> embedding =
      Optional.of(new EntityEmbedding(EmbeddingVector.of(1f, 0f), MODEL, HASH, false, NOW));
  private Optional<JobSignals> jobSignals =
      Optional.of(
          new JobSignals(
              job,
              1,
              true,
              false,
              "Backend",
              "Descripción",
              List.of(
                  new JobSignals.Requirement(
                      "SKILL", true, "Java", null, null, null, null, null))));
  private Calibration calibration = Calibration.uncalibrated(MODEL);
  private double tieTolerance = 1.0;
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

  private SuggestCandidatesForJobService service() {
    return new SuggestCandidatesForJobService(
        account -> {
          calls.add("trust");
          return verification;
        },
        new SuggestionsSources(
            (account, id) -> {
              calls.add("owned");
              return owned;
            },
            store,
            (vector, model, limit) -> {
              calls.add("retrieval");
              retrievedLimit = limit;
              retrievedModel = model;
              return pool.stream().limit(limit).map(Pooled::nearest).toList();
            },
            new CandidateSignalsReader() {
              public Optional<CandidateSignals> read(AccountId account) {
                calls.add("signals:one");
                return Optional.ofNullable(byAccount.get(account)).map(Pooled::signals);
              }

              @Override
              public Map<AccountId, CandidateSignals> readAll(Collection<AccountId> accounts) {
                calls.add("signals");
                Map<AccountId, CandidateSignals> found = new HashMap<>();
                accounts.forEach(
                    a ->
                        Optional.ofNullable(byAccount.get(a))
                            .ifPresent(p -> found.put(a, p.signals())));
                return found;
              }
            },
            id -> {
              calls.add("jobSignals");
              return jobSignals;
            },
            new AvailabilityPort() {
              public Availability visibleTo(AccountId viewer, AccountId candidate) {
                calls.add("availability:one");
                return byAccount.get(candidate).visible();
              }

              @Override
              public Map<AccountId, Availability> visibleToAll(
                  AccountId viewer, Collection<AccountId> candidates) {
                calls.add("availability");
                availabilityAsked.addAll(candidates);
                Map<AccountId, Availability> all = new HashMap<>();
                candidates.forEach(c -> all.put(c, byAccount.get(c).visible()));
                return all;
              }
            },
            scoreStore),
        new ProfileViewLogPort() {
          public void lock(AccountId viewer) {
            calls.add("lock");
          }

          public Set<CandidateProfileId> viewedSince(AccountId viewer, Instant since) {
            calls.add("viewed");
            viewedSince = since;
            return viewed;
          }

          public void append(
              AccountId viewer, JobPostingId job, List<CandidateProfileId> shown, Instant at) {
            calls.add("append");
            recorded.add(shown);
          }
        },
        new SuggestionsPolicy(5, Duration.ofHours(24), 10, 20, tieTolerance),
        new ScoringModel(Weights.defaults(), calibration),
        (useCase, outcome) -> metrics.add(outcome),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** Un candidato completo (declara Java, que es lo que pide la vacante) con el coseno dado. */
  private Pooled candidate(double cosine, Availability visible) {
    return candidate(
        cosine, visible, true, List.of(new CandidateSignals.Skill("Java", 3)), "Backend");
  }

  private Pooled candidate(
      double cosine,
      Availability visible,
      boolean discoverable,
      List<CandidateSignals.Skill> skills,
      String headline) {
    CandidateProfileId profile = new CandidateProfileId(UUID.randomUUID());
    AccountId account = new AccountId(UUID.randomUUID());
    CandidateSignals signals =
        new CandidateSignals(
            profile,
            account,
            discoverable,
            headline,
            "GT",
            null,
            null,
            false,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            skills,
            List.of(),
            List.of());
    Pooled pooled = new Pooled(new NearestProfile(profile, account, cosine), signals, visible);
    pool.add(pooled);
    byAccount.put(account, pooled);
    return pooled;
  }

  private List<CandidateProfileId> profiles(SuggestionsResult result) {
    return result.candidates().stream().map(SuggestedCandidate::profile).toList();
  }

  private SuggestionsResult ask(int size) {
    return service().execute(new SuggestCandidatesForJobQuery(recruiter, job, size));
  }

  // ---- quién puede ----

  @Test
  void aVerifiedOwnerGetsTheCandidatesBestScoreFirstAndTheyAreRecorded() {
    var low = candidate(0.4, Availability.UNSET);
    var high = candidate(0.9, Availability.UNSET);
    var mid = candidate(0.6, Availability.UNSET);

    var result = ask(10);

    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.OK);
    assertThat(profiles(result))
        .containsExactly(
            high.nearest().candidate(), mid.nearest().candidate(), low.nearest().candidate());
    assertThat(result.remainingQuota()).isEqualTo(2);
    assertThat(recorded).containsExactly(profiles(result));
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
    candidate(0.9, Availability.UNSET);

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

  // Entre la comprobación de dueño y la lectura de la vacante pudo cerrarse.
  @Test
  void aJobWhoseSignalsDisappearedMeanwhileIsNotPublished() {
    jobSignals = Optional.empty();

    assertThatThrownBy(() -> ask(5)).isInstanceOf(InvalidStateException.class);

    assertThat(metrics).containsExactly("not_published");
  }

  // ---- estados de la vacante ----

  // BR-MATCH-08: en REVIEWING deja de generar entradas nuevas.
  @Test
  void aJobInReviewingIsPausedWithNoCandidatesAndNothingIsRecorded() {
    owned = Optional.of(new OwnedJob(true, true));
    candidate(0.9, Availability.UNSET);

    var result = ask(5);

    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.PAUSED);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.remainingQuota()).isEqualTo(5);
    assertThat(result.algorithmVersion()).isNull();
    assertThat(calls).doesNotContain("retrieval", "lock", "append");
    assertThat(metrics).containsExactly("paused");
  }

  @Test
  void aJobWithoutAVectorYetIsPendingAndNothingIsRetrieved() {
    embedding = Optional.empty();
    candidate(0.9, Availability.UNSET);

    var result = ask(5);

    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.PENDING_EMBEDDING);
    assertThat(result.candidates()).isEmpty();
    assertThat(calls).doesNotContain("retrieval", "append");
    assertThat(metrics).containsExactly("pending_embedding");
  }

  @Test
  void aPausedJobStillReportsHowMuchQuotaIsLeft() {
    owned = Optional.of(new OwnedJob(true, true));
    viewed.add(new CandidateProfileId(UUID.randomUUID()));
    viewed.add(new CandidateProfileId(UUID.randomUUID()));

    assertThat(ask(5).remainingQuota()).isEqualTo(3);
  }

  // ---- la cuota ----

  @Test
  void aProfileAlreadyViewedIsFreeAndNewOnesSpendTheQuota() {
    var a = candidate(0.9, Availability.UNSET);
    var b = candidate(0.8, Availability.UNSET);
    candidate(0.7, Availability.UNSET);
    candidate(0.6, Availability.UNSET);
    viewed.add(a.nearest().candidate());
    viewed.add(b.nearest().candidate());

    var result = ask(10);

    assertThat(result.candidates()).hasSize(4);
    // Quedaban 3 (5 − 2 vistos); los 2 nuevos gastan 2.
    assertThat(result.remainingQuota()).isEqualTo(1);
  }

  @Test
  void whenTheQuotaDoesNotCoverThePageTheBestScoredFitAndItIsNotAnError() {
    List<Pooled> all = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      all.add(candidate(0.9 - i * 0.05, Availability.UNSET));
    }

    var result = ask(10);

    assertThat(profiles(result))
        .containsExactlyElementsOf(
            all.subList(0, 5).stream().map(p -> p.nearest().candidate()).toList());
    assertThat(result.remainingQuota()).isZero();
    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.OK);
  }

  @Test
  void aPageOfOnlyNewProfilesWithNoQuotaLeftIsAQuotaDenialAndNothingIsRecorded() {
    candidate(0.9, Availability.UNSET);
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
    candidate(0.9, Availability.UNSET);

    ask(1);

    assertThat(viewedSince).isEqualTo(NOW.minus(Duration.ofHours(24)));
  }

  // El candado va antes de leer lo visto y todo en una sola transacción, o dos pedidos simultáneos
  // contarían ambos «lo que queda». La cuota se aplica sobre lo ya puntuado y ordenado.
  @Test
  void theViewerIsLockedAfterScoringAndBeforeWhatWasViewedIsReadAndRecorded() {
    candidate(0.9, Availability.UNSET);
    candidate(0.8, Availability.UNSET);

    ask(2);

    assertThat(
            calls.stream().filter(c -> !c.equals("signals") && !c.equals("availability")).toList())
        .containsExactly(
            "trust", "owned", "embedding", "jobSignals", "retrieval", "lock", "viewed", "append");
  }

  // ---- recuperación y etapa 2 ----

  @Test
  void theRetrievalUsesTheJobVectorModelAndTheConfiguredCapNotThePageSize() {
    for (int i = 0; i < 30; i++) {
      candidate(0.5, Availability.UNSET);
    }

    var result = ask(3);

    assertThat(retrievedModel).isEqualTo(MODEL);
    assertThat(retrievedLimit).isEqualTo(20);
    // Se puntúan los 20 recuperados y se muestran solo los 3 pedidos.
    assertThat(result.candidates()).hasSize(3);
    assertThat(recorded.get(0)).hasSize(3);
  }

  @Test
  void aPageLargerThanThePolicyMaximumIsCappedAfterScoring() {
    for (int i = 0; i < 15; i++) {
      candidate(0.5 + i * 0.01, Availability.UNSET);
    }
    viewed.clear();

    // El tope de la política es 10 por página; con cuota 5 caben 5.
    assertThat(ask(50).candidates()).hasSize(5);
  }

  @Test
  void nobodyRetrievedMeansAnEmptyOkPageNotAQuotaDenial() {
    var result = ask(5);

    assertThat(result.status()).isEqualTo(SuggestionsResult.Status.OK);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.remainingQuota()).isEqualTo(5);
    assertThat(result.algorithmVersion()).isNull();
    assertThat(result.calibrated()).isFalse();
  }

  // ---- puntaje (BR-MATCH-01/02/04) ----

  @Test
  void everyCandidateCarriesItsScoreWithTheBreakdownAndTheAlgorithmVersion() {
    candidate(0.6, Availability.UNSET);

    var result = ask(5);

    var score = result.candidates().get(0).score();
    assertThat(score.breakdown().dimensions()).hasSize(6);
    assertThat(score.breakdown().alpha()).isEqualTo(0.30);
    assertThat(score.overall()).isBetween(0.0, 100.0);
    assertThat(result.algorithmVersion()).isEqualTo(score.version());
    assertThat(result.algorithmVersion().value()).contains(MODEL);
    // Sin calibración ajustada el puntaje se publica marcado como no calibrado.
    assertThat(result.calibrated()).isFalse();
  }

  @Test
  void aFittedCalibrationIsReportedAsCalibrated() {
    calibration = new Calibration(MODEL, Calibration.Kind.PLATT, 5, -2);
    candidate(0.6, Availability.UNSET);

    assertThat(ask(5).calibrated()).isTrue();
  }

  // R1 (ADR-0017): quien declaró menos de la completitud mínima no entra a las listas.
  @Test
  void aCandidateWhoDeclaredTooLittleIsNotSuggested() {
    candidate(0.99, Availability.UNSET, true, List.of(), "Sin habilidades");
    var complete = candidate(0.4, Availability.UNSET);

    var result = ask(5);

    assertThat(profiles(result)).containsExactly(complete.nearest().candidate());
    assertThat(recorded).containsExactly(profiles(result));
  }

  // BR-DATA-03: la copia de `discoverable` del índice puede ir un evento por detrás.
  @Test
  void aProfileThatIsNoLongerDiscoverableInItsLiveProfileIsSkipped() {
    candidate(
        0.9, Availability.UNSET, false, List.of(new CandidateSignals.Skill("Java", 3)), "Oculto");
    var visible = candidate(0.5, Availability.UNSET);

    assertThat(profiles(ask(5))).containsExactly(visible.nearest().candidate());
  }

  @Test
  void aProfileWhoseSignalsCannotBeReadIsSkipped() {
    var gone = candidate(0.9, Availability.UNSET);
    byAccount.remove(gone.nearest().account());
    var present = candidate(0.5, Availability.UNSET);

    // `availability` también consulta el mapa, pero solo se llama para quien tiene señales.
    assertThat(profiles(ask(5))).containsExactly(present.nearest().candidate());
  }

  // ---- orden final (BR-MATCH-07, BR-MATCH-09) ----

  // Mismo porcentaje mostrado: OPEN_TO_WORK primero. Un 90,0 y un 89,6 muestran ambos 90 %.
  @Test
  void openToWorkBreaksATieAtTheDisplayedPercentage() {
    // overall = 100 · (0,7 · 1 + 0,3 · coseno): 0,9 → 97; 0,899 → 96,97, ambos muestran 97 %.
    var unset = candidate(0.9, Availability.UNSET);
    var open = candidate(0.899, Availability.OPEN_TO_WORK);

    assertThat(profiles(ask(5)))
        .containsExactly(open.nearest().candidate(), unset.nearest().candidate());
  }

  @Test
  void openToWorkNeverOutranksAHigherDisplayedPercentage() {
    var high = candidate(0.9, Availability.NOT_LOOKING);
    var openButLower = candidate(0.3, Availability.OPEN_TO_WORK);

    assertThat(profiles(ask(5)))
        .containsExactly(high.nearest().candidate(), openButLower.nearest().candidate());
  }

  // BR-MATCH-09: la disponibilidad es la que ve ESTE reclutador. Si `cv` la entrega como UNSET por
  // estar excluido, el candidato no sube posiciones ni delata su estado por el orden.
  @Test
  void aCandidateWhoExcludedTheRecruiterGetsNoPriorityBoost() {
    var excludedButOpen = candidate(0.899, Availability.UNSET);
    var open = candidate(0.9, Availability.OPEN_TO_WORK);
    var plain = candidate(0.9, Availability.UNSET);

    var ranked = profiles(ask(5));

    assertThat(ranked.get(0)).isEqualTo(open.nearest().candidate());
    assertThat(ranked).contains(excludedButOpen.nearest().candidate(), plain.nearest().candidate());
    assertThat(availabilityAsked).contains(open.nearest().account());
  }

  // La tolerancia configurada llega al orden: con 4 puntos un OPEN_TO_WORK cercano adelanta.
  @Test
  void theConfiguredTieToleranceReachesTheOrdering() {
    // overall = 100 · (0,7 + 0,3 · coseno): 0,9 → 97; 0,5 → 85; 0,4 → 82.
    var higher = candidate(0.9, Availability.UNSET);
    var closeOpen = candidate(0.6, Availability.OPEN_TO_WORK);
    var narrow = profiles(ask(5));
    pool.clear();
    byAccount.clear();
    viewed.clear();
    var higher2 = candidate(0.9, Availability.UNSET);
    var closeOpen2 = candidate(0.8, Availability.OPEN_TO_WORK);
    tieTolerance = 4;

    var wide = profiles(ask(5));

    assertThat(narrow)
        .containsExactly(higher.nearest().candidate(), closeOpen.nearest().candidate());
    // 97 y 94 difieren en 3 puntos, dentro de la tolerancia de 4: OPEN_TO_WORK va primero.
    assertThat(wide)
        .containsExactly(closeOpen2.nearest().candidate(), higher2.nearest().candidate());
  }

  // TD-039: las señales y la disponibilidad se piden UNA vez por todo el conjunto recuperado, no
  // por candidato.
  @Test
  void theSignalsAndTheAvailabilityAreAskedOnceForTheWholeRecoveredSet() {
    for (int i = 0; i < 15; i++) {
      candidate(0.5 + i * 0.01, Availability.UNSET);
    }

    ask(10);

    assertThat(calls.stream().filter(c -> c.startsWith("signals")).toList())
        .containsExactly("signals");
    assertThat(calls.stream().filter(c -> c.startsWith("availability")).toList())
        .containsExactly("availability");
    // La disponibilidad solo se pide de quienes pasaron el filtro de completitud, todos juntos.
    assertThat(availabilityAsked).hasSize(15);
  }

  @Test
  void availabilityIsNotAskedForWhoWasDiscardedForBeingIncomplete() {
    candidate(0.9, Availability.UNSET, true, List.of(), "Sin habilidades");
    var complete = candidate(0.5, Availability.UNSET);

    ask(5);

    assertThat(availabilityAsked).containsExactly(complete.nearest().account());
  }

  // TD-036: lo que un reclutador vio es un par con motivo; su puntaje vigente se guarda, una sola
  // vez.
  @Test
  void theShownCandidatesGetTheirCurrentScoreStoredWithTheSuggestedReason() {
    var shown = candidate(0.9, Availability.UNSET);
    var second = candidate(0.8, Availability.UNSET);

    var result = ask(5);

    assertThat(scoreStore.upsertCalls).isEqualTo(1);
    assertThat(scoreStore.current.values())
        .extracting(s -> s.profile())
        .containsExactlyInAnyOrder(shown.nearest().candidate(), second.nearest().candidate());
    assertThat(scoreStore.current.values())
        .allSatisfy(
            s -> {
              assertThat(s.reason()).isEqualTo(ScoreReason.SUGGESTED);
              assertThat(s.job()).isEqualTo(job);
            });
    var first = result.candidates().get(0);
    assertThat(scoreStore.current.values())
        .filteredOn(s -> s.profile().equals(first.profile()))
        .singleElement()
        .satisfies(s -> assertThat(s.score()).isEqualTo(first.score()));
  }

  @Test
  void onlyWhatWasShownIsStoredNotEveryScoredCandidate() {
    for (int i = 0; i < 8; i++) {
      candidate(0.9 - i * 0.05, Availability.UNSET);
    }

    ask(2);

    assertThat(scoreStore.current).hasSize(2);
  }

  @Test
  void aDeniedRequestStoresNothing() {
    candidate(0.9, Availability.UNSET);
    verification = Optional.of(RecruiterVerification.UNVERIFIED);

    assertThatThrownBy(() -> ask(5)).isInstanceOf(SuggestionsDeniedException.class);

    assertThat(scoreStore.current).isEmpty();
  }

  @Test
  void aPausedJobStoresNothing() {
    owned = Optional.of(new OwnedJob(true, true));
    candidate(0.9, Availability.UNSET);

    ask(5);

    assertThat(scoreStore.upsertCalls).isZero();
  }

  @Test
  void theAvailabilityIsShownAsTheRecruiterSeesIt() {
    candidate(0.9, Availability.NOT_LOOKING);

    assertThat(ask(5).candidates().get(0).availability()).isEqualTo(Availability.NOT_LOOKING);
  }

  @Test
  void theAvailabilityDoesNotChangeTheScore() {
    var open = candidate(0.7, Availability.OPEN_TO_WORK);
    var unset = candidate(0.7, Availability.UNSET);

    var scores = ask(5).candidates().stream().map(c -> c.score().overall()).distinct().toList();

    assertThat(scores).hasSize(1);
    assertThat(open).isNotEqualTo(unset);
  }

  // ---- tarjeta enmascarada (REQ-C-07) ----

  @Test
  void theCardHasNoContactDataEvenIfTheHeadlineHadSome() {
    candidate(
        0.9,
        Availability.UNSET,
        true,
        List.of(new CandidateSignals.Skill("Java", 3)),
        "Backend, escríbeme a ana@correo.test");

    var card = ask(5).candidates().get(0).card();

    assertThat(card.headline()).doesNotContain("ana@correo.test").contains("Backend");
    assertThat(card.country()).isEqualTo("GT");
    assertThat(card.topSkills()).containsExactly("Java");
  }

  // BR-DATA-01: ni la métrica lleva el identificador de la cuenta, de la vacante o de un candidato.
  @Test
  void theMetricsCarryNoIdentifiers() {
    candidate(0.9, Availability.UNSET);

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
