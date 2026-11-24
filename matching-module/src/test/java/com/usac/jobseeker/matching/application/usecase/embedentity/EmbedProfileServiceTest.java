package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.CandidateSignals;
import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El cálculo de un perfil: nivel disparado, hash, orden de los pasos y la lápida de la
 * anonimización.
 */
class EmbedProfileServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final CandidateProfileId profile = new CandidateProfileId(UUID.randomUUID());
  private final InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
  private final List<EmbeddableText> sent = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private Optional<CandidateSignals> signals = Optional.of(signals(true, "Backend", "Java"));
  private String model = "modelo-a";
  private RuntimeException clientFailure;

  private final EmbedProfileService service =
      new EmbedProfileService(
          accountId -> signals,
          new EmbeddingClientPort() {
            public String model() {
              return model;
            }

            public Embedded embed(EmbeddableText text) {
              store.operations.add("embed");
              sent.add(text);
              if (clientFailure != null) {
                throw clientFailure;
              }
              return new Embedded(EmbeddingVector.of(0.1f, 0.2f, 0.3f), model, false);
            }
          },
          store,
          (useCase, outcome) -> metrics.add(useCase + ":" + outcome),
          Clock.fixed(NOW, ZoneOffset.UTC),
          100_000);

  private CandidateSignals signals(boolean discoverable, String headline, String skill) {
    return new CandidateSignals(
        profile,
        account,
        discoverable,
        headline,
        "GT",
        "REMOTE",
        "FULL_TIME",
        false,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(new CandidateSignals.Skill(skill, null)),
        List.of(),
        List.of());
  }

  private EmbedProfileUseCase.Result run() {
    return service.execute(new EmbedProfileCommand(account));
  }

  @Test
  void aNewProfileIsRegisteredMadeDiscoverableThenEmbeddedAndSaved() {
    var result = run();

    assertThat(result).isEqualTo(EmbedProfileUseCase.Result.EMBEDDED);
    assertThat(store.embeddings.get(profile).model()).isEqualTo("modelo-a");
    assertThat(store.embeddings.get(profile).computedAt()).isEqualTo(NOW);
    assertThat(store.accounts).containsEntry(account, profile);
    assertThat(metrics).containsExactly("embed_profile:embedded");
  }

  // Ocultar un perfil debe notarse al instante: la descubribilidad se sincroniza antes del modelo.
  @Test
  void discoverabilityIsSyncedBeforeTheModelIsCalled() {
    signals = Optional.of(signals(false, "Backend", "Java"));

    run();

    assertThat(store.operations).containsExactly("register", "discoverable=false", "embed", "save");
    assertThat(store.discoverable.get(profile)).isFalse();
  }

  @Test
  void ifTheContentDidNotChangeTheModelIsNotCalledAgainAndOnlyTheFlagIsSynced() {
    run();
    sent.clear();
    signals = Optional.of(signals(false, "Backend", "Java"));

    var result = run();

    assertThat(result).isEqualTo(EmbedProfileUseCase.Result.UNCHANGED);
    assertThat(sent).isEmpty();
    assertThat(store.discoverable.get(profile)).isFalse();
    assertThat(metrics).containsExactly("embed_profile:embedded", "embed_profile:unchanged");
  }

  @Test
  void aChangeInTheContentCallsTheModelAgain() {
    run();
    signals = Optional.of(signals(true, "Backend", "Rust"));

    assertThat(run()).isEqualTo(EmbedProfileUseCase.Result.EMBEDDED);
    assertThat(sent).hasSize(2);
  }

  @Test
  void aChangeOfModelEmbedsAgainEvenIfTheContentIsTheSame() {
    run();
    model = "modelo-b";

    assertThat(run()).isEqualTo(EmbedProfileUseCase.Result.EMBEDDED);
    assertThat(store.embeddings.get(profile).model()).isEqualTo("modelo-b");
  }

  @Test
  void onlyRedactedTextEverReachesTheModel() {
    signals =
        Optional.of(
            new CandidateSignals(
                profile,
                account,
                true,
                "Escríbeme a ana@example.com",
                null,
                null,
                null,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

    run();

    assertThat(sent)
        .singleElement()
        .satisfies(
            text ->
                assertThat(text.value()).doesNotContain("ana@example.com").contains("[correo]"));
  }

  @Test
  void anUnreadableProfileOfAKnownAccountStopsBeingDiscoverableButKeepsItsVector() {
    run();
    signals = Optional.empty();

    var result = run();

    assertThat(result).isEqualTo(EmbedProfileUseCase.Result.HIDDEN);
    assertThat(store.discoverable.get(profile)).isFalse();
    assertThat(store.embeddings).containsKey(profile);
  }

  @Test
  void anAccountMatchingNeverKnewHasNothingToDo() {
    signals = Optional.empty();

    assertThat(run()).isEqualTo(EmbedProfileUseCase.Result.NOTHING_TO_DO);
    assertThat(sent).isEmpty();
  }

  @Test
  void anAnonymizedAccountIsNeverEmbeddedAgainEvenIfAStaleReadStillReturnsItsProfile() {
    store.buried.add(account);

    var result = run();

    assertThat(result).isEqualTo(EmbedProfileUseCase.Result.NOTHING_TO_DO);
    assertThat(sent).isEmpty();
    assertThat(store.embeddings).isEmpty();
  }

  // La carrera de la anonimización: llega mientras el modelo trabaja y el vector no debe quedar.
  @Test
  void ifTheAccountIsAnonymizedWhileTheModelWorksNoVectorIsLeftBehind() {
    store.buryDuringSave = true;

    var result = run();

    assertThat(result).isEqualTo(EmbedProfileUseCase.Result.NOTHING_TO_DO);
    assertThat(store.embeddings).isEmpty();
  }

  @Test
  void aModelFailurePropagatesSoTheQueueCanDecideWhatToDo() {
    clientFailure = new EmbeddingUnavailableException("no responde", null);

    assertThatThrownBy(this::run).isInstanceOf(EmbeddingUnavailableException.class);

    assertThat(store.embeddings).isEmpty();
    assertThat(metrics).isEmpty();
  }

  @Test
  void aNonPositiveBudgetIsRejectedAtConstruction() {
    EmbeddingClientPort anyClient =
        new EmbeddingClientPort() {
          public String model() {
            return model;
          }

          public Embedded embed(EmbeddableText text) {
            throw new UnsupportedOperationException();
          }
        };

    assertThatThrownBy(
            () ->
                new EmbedProfileService(
                    accountId -> Optional.empty(),
                    anyClient,
                    store,
                    (u, o) -> {},
                    Clock.systemUTC(),
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
