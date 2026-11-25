package com.usac.jobseeker.matching.application.usecase.embedentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.application.observability.UseCaseMetricsPort;
import com.usac.jobseeker.matching.domain.model.EmbeddableText;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.domain.model.JobSignals;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El cálculo de una vacante: solo una publicada se embebe, las copias se sincronizan antes del
 * modelo, y el *hash* evita llamarlo de más.
 */
class EmbedJobServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

  private final JobPostingId job = new JobPostingId(UUID.randomUUID());
  private final InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
  private final List<EmbeddableText> sent = new ArrayList<>();
  private final List<String> metrics = new ArrayList<>();
  private Optional<JobSignals> signals = Optional.of(signals(true, false, "Backend", "Java"));
  private String model = "modelo-a";
  private RuntimeException clientFailure;

  private final EmbedJobService service =
      new EmbedJobService(
          id -> signals,
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

  private JobSignals signals(boolean published, boolean reviewing, String title, String skill) {
    return new JobSignals(
        job,
        1,
        published,
        reviewing,
        title,
        "Descripción",
        List.of(new JobSignals.Requirement("SKILL", true, skill, null, null, null, null, null)));
  }

  private EmbedJobUseCase.Result run() {
    return service.execute(new EmbedJobCommand(job));
  }

  @Test
  void aPublishedJobIsEmbeddedAndItsCopiesAreSet() {
    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.EMBEDDED);

    assertThat(store.jobEmbeddings.get(job).model()).isEqualTo("modelo-a");
    assertThat(store.jobEmbeddings.get(job).computedAt()).isEqualTo(NOW);
    assertThat(store.jobCopies.get(job)).containsExactly(true, false);
    assertThat(metrics).containsExactly("embed_job:embedded");
  }

  // Cerrar una vacante debe notarse al instante: las copias se sincronizan antes del modelo.
  @Test
  void theCopiesAreSetBeforeTheModelIsCalled() {
    run();

    assertThat(store.operations).containsExactly("copies-job", "embed", "save-job");
  }

  // BR-MATCH-08: la etapa de reclutamiento se copia sin recalcular nada.
  @Test
  void aJobInReviewingKeepsItsVectorAndIsMarkedAsReviewing() {
    run();
    sent.clear();
    signals = Optional.of(signals(true, true, "Backend", "Java"));

    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.UNCHANGED);

    assertThat(store.jobCopies.get(job)).containsExactly(true, true);
    assertThat(sent).isEmpty();
  }

  // BR-MATCH-03: nivel disparado y por contenido; releer lo mismo no llama al modelo.
  @Test
  void anUnchangedJobDoesNotCallTheModelAgain() {
    run();
    sent.clear();

    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.UNCHANGED);

    assertThat(sent).isEmpty();
    assertThat(metrics).containsExactly("embed_job:embedded", "embed_job:unchanged");
  }

  @Test
  void aChangeOfContentIsEmbeddedAgain() {
    run();
    signals = Optional.of(signals(true, false, "Backend Senior", "Java"));

    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.EMBEDDED);

    assertThat(sent).hasSize(2);
  }

  @Test
  void aChangeOfModelIsEmbeddedAgainEvenIfTheContentIsTheSame() {
    run();
    model = "modelo-b";

    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.EMBEDDED);

    assertThat(store.jobEmbeddings.get(job).model()).isEqualTo("modelo-b");
  }

  // Una cerrada o archivada deja de ser candidata, pero conserva el vector: los puntajes ya
  // calculados se siguen pudiendo explicar.
  @Test
  void aJobThatIsNoLongerPublishedKeepsItsVectorButIsNotACandidate() {
    run();
    sent.clear();
    signals = Optional.of(signals(false, false, "Backend", "Java"));

    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.NOT_PUBLISHED);

    assertThat(store.jobCopies.get(job)).containsExactly(false, false);
    assertThat(store.jobEmbeddings).containsKey(job);
    assertThat(sent).isEmpty();
  }

  @Test
  void aJobThatWasNeverPublishedIsNotEmbeddedEvenIfItIsReadable() {
    signals = Optional.of(signals(false, false, "Backend", "Java"));

    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.NOT_PUBLISHED);

    assertThat(sent).isEmpty();
    assertThat(store.jobEmbeddings).isEmpty();
  }

  // Eliminada (o borrador descartado): job ya no la entrega. Si `matching` la conocía, deja de ser
  // candidata; si no, no se crea una fila vacía.
  @Test
  void aJobThatCanNoLongerBeReadIsUnpublishedIfKnownAndIgnoredIfNot() {
    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.EMBEDDED);
    signals = Optional.empty();

    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.NOT_PUBLISHED);
    assertThat(store.jobCopies.get(job)).containsExactly(false, false);
    assertThat(store.jobEmbeddings).containsKey(job);

    store.jobCopies.clear();
    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.NOTHING_TO_DO);
    assertThat(store.jobCopies).isEmpty();
  }

  @Test
  void anUnreadableJobUnknownToMatchingLeavesNoTrace() {
    signals = Optional.empty();

    assertThat(run()).isEqualTo(EmbedJobUseCase.Result.NOTHING_TO_DO);

    assertThat(store.operations).isEmpty();
  }

  // El rechazo y la no disponibilidad del modelo salen tal cual: los trata la cola.
  @Test
  void aFailureOfTheModelPropagatesAndLeavesNoVector() {
    clientFailure = new EmbeddingUnavailableException("no responde", null);

    assertThatThrownBy(this::run).isInstanceOf(EmbeddingUnavailableException.class);

    assertThat(store.jobEmbeddings).isEmpty();
    assertThat(store.jobCopies.get(job)).containsExactly(true, false);
  }

  @Test
  void whatIsSentToTheModelIsTheRedactedCanonicalText() {
    signals = Optional.of(new JobSignals(job, 1, true, false, "T", "Escribe a x@y.com", List.of()));

    run();

    assertThat(sent)
        .singleElement()
        .satisfies(t -> assertThat(t.value()).doesNotContain("x@y.com"));
  }

  @Test
  void neitherTheMetricNorAnythingElseCarriesTheJobIdentifier() {
    run();

    assertThat(metrics).noneMatch(m -> m.contains(job.toString()));
  }

  @Test
  void theBudgetOfCharactersMustBePositive() {
    EmbeddingClientPort client =
        new EmbeddingClientPort() {
          public String model() {
            return model;
          }

          public Embedded embed(EmbeddableText text) {
            throw new UnsupportedOperationException();
          }
        };
    JobSignalsReader reader = id -> signals;
    UseCaseMetricsPort noMetrics = (useCase, outcome) -> {};
    Clock clock = Clock.systemUTC();

    assertThatThrownBy(() -> new EmbedJobService(reader, client, store, noMetrics, clock, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
