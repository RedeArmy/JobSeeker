package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.NearestProfile;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El adaptador contra las tablas reales que crea la migración del módulo en Postgres 17 con
 * pgvector (ADR-0028): ida y vuelta de los vectores, las reglas de las copias de `discoverable` y
 * de «publicada», la anonimización y la recuperación por similitud, incluido que usa el índice
 * parcial.
 */
class JdbcEmbeddingRepositoryTest {

  private static final int DIMENSIONS = 1024;
  private static final String MODEL = "qwen3-embedding:0.6b";
  private static final String HASH = "b".repeat(64);
  private static final Instant NOW = Instant.parse("2026-11-23T10:00:00.123456Z");

  private final JdbcClient jdbc = JdbcClient.create(MatchingPostgres.dataSource());
  private final JdbcEmbeddingRepository repository = new JdbcEmbeddingRepository(jdbc);

  private static CandidateProfileId profile() {
    return new CandidateProfileId(UUID.randomUUID());
  }

  /**
   * Un perfil ligado a una cuenta, como llega por la bandeja: la recuperación devuelve la cuenta.
   */
  private CandidateProfileId registeredProfile() {
    CandidateProfileId id = profile();
    repository.registerProfile(new AccountId(UUID.randomUUID()), id);
    return id;
  }

  private static JobPostingId job() {
    return new JobPostingId(UUID.randomUUID());
  }

  /** Un vector de 1024 dimensiones con las primeras fijadas y el resto en cero. */
  private static EmbeddingVector vector(float... head) {
    float[] values = new float[DIMENSIONS];
    System.arraycopy(head, 0, values, 0, head.length);
    return EmbeddingVector.of(values);
  }

  private static EntityEmbedding embedding(EmbeddingVector vector, String model) {
    return new EntityEmbedding(vector, model, HASH, false, NOW);
  }

  private static EntityEmbedding embedding(EmbeddingVector vector) {
    return embedding(vector, MODEL);
  }

  // ---- perfiles: ida y vuelta ----

  @Test
  void aProfileNobodyKnowsHasNoStateAndNoVector() {
    CandidateProfileId id = profile();

    assertThat(repository.findProfileState(id)).isEmpty();
    assertThat(repository.findProfileEmbedding(id)).isEmpty();
  }

  @Test
  void aSavedVectorIsReadBackWithEveryField() {
    CandidateProfileId id = profile();
    float[] values = new float[DIMENSIONS];
    Random random = new Random(7);
    for (int i = 0; i < DIMENSIONS; i++) {
      values[i] = random.nextFloat() * 2 - 1;
    }
    var saved = new EntityEmbedding(EmbeddingVector.of(values), MODEL, HASH, true, NOW);

    repository.saveProfileEmbedding(id, true, saved);

    assertThat(repository.findProfileEmbedding(id)).contains(saved);
  }

  @Test
  void theStateDescribesTheVectorWithoutReadingIt() {
    CandidateProfileId id = profile();
    repository.saveProfileEmbedding(
        id, true, new EntityEmbedding(vector(1f), MODEL, HASH, true, NOW));

    var state = repository.findProfileState(id).orElseThrow();

    assertThat(state.discoverable()).isTrue();
    assertThat(state.embedding())
        .get()
        .satisfies(
            meta -> {
              assertThat(meta.model()).isEqualTo(MODEL);
              assertThat(meta.contentHash()).isEqualTo(HASH);
              assertThat(meta.truncated()).isTrue();
              assertThat(meta.computedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
            });
  }

  @Test
  void savingAgainReplacesTheVectorAndItsMetadata() {
    CandidateProfileId id = profile();
    repository.saveProfileEmbedding(id, true, embedding(vector(1f)));
    var newer =
        new EntityEmbedding(
            vector(0f, 1f), "otro-modelo", "c".repeat(64), false, NOW.plusSeconds(60));

    repository.saveProfileEmbedding(id, true, newer);

    assertThat(repository.findProfileEmbedding(id)).contains(newer);
  }

  // ---- la copia de discoverable: el evento posterior gana ----

  @Test
  void theFlagGivenWhenCreatingTheRowIsTheInitialOne() {
    CandidateProfileId hidden = profile();
    CandidateProfileId visible = profile();

    repository.saveProfileEmbedding(hidden, false, embedding(vector(1f)));
    repository.saveProfileEmbedding(visible, true, embedding(vector(1f)));

    assertThat(repository.findProfileState(hidden))
        .get()
        .extracting(s -> s.discoverable())
        .isEqualTo(false);
    assertThat(repository.findProfileState(visible))
        .get()
        .extracting(s -> s.discoverable())
        .isEqualTo(true);
  }

  @Test
  void aLaterSaveNeverFlipsTheCopyThatAnEventAlreadySet() {
    CandidateProfileId id = profile();
    repository.saveProfileEmbedding(id, true, embedding(vector(1f)));
    repository.setProfileDiscoverable(id, false);

    // Un cálculo lento que leyó «descubrible» antes de que el usuario lo apagara no puede
    // reabrirlo.
    repository.saveProfileEmbedding(id, true, embedding(vector(0f, 1f)));

    assertThat(repository.findProfileState(id))
        .get()
        .extracting(s -> s.discoverable())
        .isEqualTo(false);
  }

  @Test
  void anEventThatArrivesBeforeTheFirstVectorIsNotLost() {
    CandidateProfileId id = profile();

    repository.setProfileDiscoverable(id, false);
    assertThat(repository.findProfileState(id))
        .get()
        .satisfies(
            state -> {
              assertThat(state.discoverable()).isFalse();
              assertThat(state.embedding()).isEmpty();
            });

    repository.saveProfileEmbedding(id, true, embedding(vector(1f)));

    // El snapshot decía «descubrible», pero el evento ya había dicho que no, y el evento es
    // posterior.
    assertThat(repository.findProfileState(id))
        .get()
        .extracting(s -> s.discoverable())
        .isEqualTo(false);
  }

  @Test
  void theFlagCanBeTurnedOnAndOffRepeatedly() {
    CandidateProfileId id = profile();

    repository.setProfileDiscoverable(id, true);
    repository.setProfileDiscoverable(id, true);
    assertThat(repository.findProfileState(id))
        .get()
        .extracting(s -> s.discoverable())
        .isEqualTo(true);
    repository.setProfileDiscoverable(id, false);
    assertThat(repository.findProfileState(id))
        .get()
        .extracting(s -> s.discoverable())
        .isEqualTo(false);
  }

  // ---- anonimización (BR-DATA-05) ----

  @Test
  void anonymizingErasesTheWholeVectorBlockAndHidesTheProfileButKeepsTheRow() {
    CandidateProfileId id = profile();
    repository.saveProfileEmbedding(
        id, true, new EntityEmbedding(vector(1f), MODEL, HASH, true, NOW));

    assertThat(repository.clearProfileEmbedding(id)).isTrue();

    assertThat(repository.findProfileEmbedding(id)).isEmpty();
    assertThat(repository.findProfileState(id))
        .get()
        .satisfies(
            state -> {
              assertThat(state.discoverable()).isFalse();
              assertThat(state.embedding()).isEmpty();
            });
  }

  @Test
  void anonymizingTwiceIsHarmlessAndAnUnknownProfileReportsNothingToErase() {
    CandidateProfileId id = profile();
    repository.saveProfileEmbedding(id, true, embedding(vector(1f)));

    assertThat(repository.clearProfileEmbedding(id)).isTrue();
    assertThat(repository.clearProfileEmbedding(id)).isTrue();
    assertThat(repository.clearProfileEmbedding(profile())).isFalse();
  }

  // ---- la base impide un vector sin saber de qué modelo es ----

  @Test
  void theDatabaseRefusesAVectorWithoutItsModelAndAModelWithoutItsVector() {
    CandidateProfileId id = profile();

    assertThatThrownBy(() -> insertVectorWithoutModel(id)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> insertModelWithoutVector(id)).isInstanceOf(DataAccessException.class);
  }

  private void insertVectorWithoutModel(CandidateProfileId id) {
    jdbc.sql(
            "insert into profile_embeddings (candidate_profile_id, embedding)"
                + " values (:id, cast(:v as vector))")
        .param("id", id.value())
        .param("v", JdbcEmbeddingRepository.toLiteral(vector(1f)))
        .update();
  }

  private void insertModelWithoutVector(CandidateProfileId id) {
    jdbc.sql(
            "insert into profile_embeddings (candidate_profile_id, embedding_model)"
                + " values (:id, 'm')")
        .param("id", id.value())
        .update();
  }

  @Test
  void aVectorOfTheWrongSizeIsRefusedBecauseTheColumnHasAFixedDimension() {
    var tiny = new EntityEmbedding(EmbeddingVector.of(1f, 2f, 3f), MODEL, HASH, false, NOW);

    CandidateProfileId profileId = profile();
    JobPostingId jobId = job();

    assertThatThrownBy(() -> repository.saveProfileEmbedding(profileId, true, tiny))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> repository.saveJobEmbedding(jobId, true, tiny))
        .isInstanceOf(DataAccessException.class);
  }

  // ---- recuperación por similitud ----

  @Test
  void theNearestDiscoverableProfilesComeBackFromMostToLeastSimilar() {
    String model = "modelo-" + UUID.randomUUID();
    CandidateProfileId same = registeredProfile();
    CandidateProfileId near = registeredProfile();
    CandidateProfileId orthogonal = registeredProfile();
    CandidateProfileId opposite = registeredProfile();
    repository.saveProfileEmbedding(same, true, embedding(vector(1f), model));
    repository.saveProfileEmbedding(near, true, embedding(vector(1f, 1f), model));
    repository.saveProfileEmbedding(orthogonal, true, embedding(vector(0f, 1f), model));
    repository.saveProfileEmbedding(opposite, true, embedding(vector(-1f), model));

    List<NearestProfile> nearest = repository.nearestDiscoverable(vector(1f), model, 10);

    assertThat(nearest)
        .extracting(NearestProfile::candidate)
        .containsExactly(same, near, orthogonal, opposite);
    assertThat(nearest.get(0).cosineSimilarity())
        .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(nearest.get(1).cosineSimilarity())
        .isCloseTo(Math.sqrt(0.5), org.assertj.core.data.Offset.offset(1e-6));
    assertThat(nearest.get(2).cosineSimilarity())
        .isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(nearest.get(3).cosineSimilarity())
        .isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-6));
  }

  @Test
  void aProfileThatIsNotDiscoverableOrWasAnonymizedOrHasNoVectorNeverComesBack() {
    String model = "modelo-" + UUID.randomUUID();
    CandidateProfileId visible = registeredProfile();
    CandidateProfileId hidden = registeredProfile();
    CandidateProfileId anonymized = registeredProfile();
    CandidateProfileId withoutVector = registeredProfile();
    repository.saveProfileEmbedding(visible, true, embedding(vector(1f), model));
    repository.saveProfileEmbedding(hidden, false, embedding(vector(1f), model));
    repository.saveProfileEmbedding(anonymized, true, embedding(vector(1f), model));
    repository.clearProfileEmbedding(anonymized);
    repository.setProfileDiscoverable(withoutVector, true);

    assertThat(repository.nearestDiscoverable(vector(1f), model, 10))
        .extracting(NearestProfile::candidate)
        .containsExactly(visible);
  }

  // La etapa 2 lee las señales por cuenta: la recuperación debe devolver la cuenta dueña del
  // perfil.
  @Test
  void eachNearestProfileCarriesTheAccountThatOwnsIt() {
    String model = "modelo-" + UUID.randomUUID();
    AccountId owner = new AccountId(UUID.randomUUID());
    CandidateProfileId mine = profile();
    repository.registerProfile(owner, mine);
    repository.saveProfileEmbedding(mine, true, embedding(vector(1f), model));

    assertThat(repository.nearestDiscoverable(vector(1f), model, 5))
        .singleElement()
        .satisfies(
            n -> {
              assertThat(n.candidate()).isEqualTo(mine);
              assertThat(n.account()).isEqualTo(owner);
            });
  }

  // Una cuenta con lápida no se devuelve nunca, aunque su vector siguiera en la tabla (BR-DATA-05).
  @Test
  void anAccountWithATombstoneIsNeverReturnedEvenIfItsVectorRemained() {
    String model = "modelo-" + UUID.randomUUID();
    AccountId gone = new AccountId(UUID.randomUUID());
    CandidateProfileId profile = profile();
    repository.registerProfile(gone, profile);
    repository.saveProfileEmbedding(profile, true, embedding(vector(1f), model));
    jdbc.sql("update profile_embedding_accounts set anonymized_at = now() where account_id = :a")
        .param("a", gone.value())
        .update();

    assertThat(repository.nearestDiscoverable(vector(1f), model, 5)).isEmpty();
  }

  // Un perfil con vector pero sin cuenta conocida no puede puntuarse: no se devuelve.
  @Test
  void aProfileWithoutAKnownAccountIsNotReturned() {
    String model = "modelo-" + UUID.randomUUID();
    repository.saveProfileEmbedding(profile(), true, embedding(vector(1f), model));

    assertThat(repository.nearestDiscoverable(vector(1f), model, 5)).isEmpty();
  }

  @Test
  void onlyVectorsOfTheRequestedModelAreComparedAndTheLimitIsRespected() {
    String current = "modelo-" + UUID.randomUUID();
    CandidateProfileId a = registeredProfile();
    CandidateProfileId b = registeredProfile();
    CandidateProfileId otherModel = registeredProfile();
    repository.saveProfileEmbedding(a, true, embedding(vector(1f), current));
    repository.saveProfileEmbedding(b, true, embedding(vector(1f, 1f), current));
    repository.saveProfileEmbedding(otherModel, true, embedding(vector(1f), "otro-" + current));

    assertThat(repository.nearestDiscoverable(vector(1f), current, 10))
        .extracting(NearestProfile::candidate)
        .containsExactly(a, b);
    assertThat(repository.nearestDiscoverable(vector(1f), current, 1))
        .extracting(NearestProfile::candidate)
        .containsExactly(a);
    assertThat(repository.nearestDiscoverable(vector(1f), current, 0)).isEmpty();
    assertThat(repository.nearestDiscoverable(vector(1f), current, -3)).isEmpty();
  }

  @Test
  void theRetrievalQueryCanUseThePartialIndexBecauseItsConditionMatches() throws Exception {
    // En una tabla pequeña el planificador prefiere el índice por modelo o recorrerla. Para saber
    // si la
    // consulta PODRÍA usar el HNSW parcial, dentro de una transacción que se revierte se quita el
    // índice por modelo y se prohíbe el recorrido completo. Si la condición de la consulta no
    // coincidiera con la del índice parcial, no podría y esta prueba lo vería.
    String literal = JdbcEmbeddingRepository.toLiteral(vector(1f));
    List<String> plan = new ArrayList<>();
    try (Connection connection = MatchingPostgres.dataSource().getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        statement.execute("drop index idx_pe_embedding_model");
        statement.execute("set local enable_seqscan = off");
        try (ResultSet rows =
            statement.executeQuery(
                "explain select candidate_profile_id, 1 - (embedding <=> cast('"
                    + literal
                    + "' as vector))"
                    + " from profile_embeddings where discoverable and embedding is not null"
                    + " and embedding_model = 'm' order by embedding <=> cast('"
                    + literal
                    + "' as vector) limit 5")) {
          while (rows.next()) {
            plan.add(rows.getString(1));
          }
        }
      } finally {
        connection.rollback();
      }
    }

    assertThat(String.join("\n", plan)).contains("idx_pe_embedding_hnsw");
    assertThat(
            jdbc.sql("select count(*) from pg_indexes where indexname = 'idx_pe_embedding_model'")
                .query(Long.class)
                .single())
        .as("la transacción se revirtió y el índice sigue ahí")
        .isEqualTo(1L);
  }

  // ---- vacantes ----

  @Test
  void aJobBehavesLikeAProfileForTheVectorAndItsCopyOfPublished() {
    JobPostingId id = job();
    assertThat(repository.findJobState(id)).isEmpty();
    assertThat(repository.findJobEmbedding(id)).isEmpty();
    var saved = new EntityEmbedding(vector(1f, 2f), MODEL, HASH, true, NOW);

    repository.saveJobEmbedding(id, false, saved);

    assertThat(repository.findJobEmbedding(id)).contains(saved);
    assertThat(repository.findJobState(id))
        .get()
        .satisfies(
            state -> {
              assertThat(state.published()).isFalse();
              assertThat(state.embedding()).isPresent();
            });
  }

  @Test
  void thePublishedCopyFollowsTheSameRuleThatTheLaterEventWins() {
    JobPostingId id = job();
    repository.saveJobEmbedding(id, false, embedding(vector(1f)));
    repository.setJobCopies(id, true, false);

    repository.saveJobEmbedding(id, false, embedding(vector(0f, 1f)));

    assertThat(repository.findJobState(id)).get().extracting(s -> s.published()).isEqualTo(true);
  }

  @Test
  void aPublishedEventBeforeTheFirstVectorCreatesTheRowWithoutOne() {
    JobPostingId id = job();

    repository.setJobCopies(id, true, false);

    assertThat(repository.findJobState(id))
        .get()
        .satisfies(
            state -> {
              assertThat(state.published()).isTrue();
              assertThat(state.embedding()).isEmpty();
            });
    assertThat(repository.findJobEmbedding(id)).isEmpty();
  }

  // BR-MATCH-08: la etapa de reclutamiento es una copia que mantienen los eventos de `job`.
  @Test
  void theRecruitingStageIsACopyThatDefaultsToHiringAndFollowsTheLatestEvent() {
    JobPostingId id = job();
    repository.setJobCopies(id, true, false);
    assertThat(repository.findJobState(id)).get().extracting(s -> s.reviewing()).isEqualTo(false);

    repository.setJobCopies(id, true, true);
    assertThat(repository.findJobState(id)).get().extracting(s -> s.reviewing()).isEqualTo(true);

    repository.setJobCopies(id, true, false);
    assertThat(repository.findJobState(id)).get().extracting(s -> s.reviewing()).isEqualTo(false);
  }

  @Test
  void savingAVectorNeverChangesTheStageCopyOfAnExistingRow() {
    JobPostingId id = job();
    repository.setJobCopies(id, true, true);

    repository.saveJobEmbedding(id, true, embedding(vector(1f)));

    assertThat(repository.findJobState(id)).get().extracting(s -> s.reviewing()).isEqualTo(true);
  }

  @Test
  void aNewRowCreatedBySavingAVectorStartsInHiring() {
    JobPostingId id = job();

    repository.saveJobEmbedding(id, true, embedding(vector(1f)));

    assertThat(repository.findJobState(id)).get().extracting(s -> s.reviewing()).isEqualTo(false);
  }

  @Test
  void theDatabaseRejectsAnUnknownStage() {
    JobPostingId id = job();
    repository.setJobCopies(id, true, false);

    assertThatThrownBy(() -> setRawStage(id, "PAUSED"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private void setRawStage(JobPostingId id, String stage) {
    jdbc.sql("update job_embeddings set recruiting_stage = :stage where job_posting_id = :id")
        .param("stage", stage)
        .param("id", id.value())
        .update();
  }

  // ---- conversión de texto ----

  @Test
  void theTextFormOfAVectorRoundTrips() {
    var original = EmbeddingVector.of(0.1f, -2.5f, 1e-7f, 123456.78f);

    assertThat(JdbcEmbeddingRepository.fromLiteral(JdbcEmbeddingRepository.toLiteral(original)))
        .isEqualTo(original);
  }
}
