package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingEntity;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La similitud entre el vector de un perfil y el de una vacante, y el directorio cuenta-perfil,
 * contra las tablas reales: solo se compara con el mismo modelo y con ambos vectores presentes
 * (ADR-0017, decisión 1; ADR-0018), y una cuenta anonimizada no aparece (BR-DATA-05).
 */
class JdbcPairSimilarityTest {

  private static final int DIMENSIONS = 1024;
  private static final String HASH = "d".repeat(64);
  private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
  private static final org.assertj.core.data.Offset<Double> TOL =
      org.assertj.core.data.Offset.offset(1e-6);

  private final JdbcClient jdbc = JdbcClient.create(MatchingPostgres.dataSource());
  private final JdbcEmbeddingRepository repository = new JdbcEmbeddingRepository(jdbc);
  private final JdbcEmbeddingWorkQueue queue = new JdbcEmbeddingWorkQueue(jdbc);

  @BeforeEach
  void clean() {
    jdbc.sql("delete from matching_work_queue").update();
  }

  private static EmbeddingVector vector(float... head) {
    float[] values = new float[DIMENSIONS];
    System.arraycopy(head, 0, values, 0, head.length);
    return EmbeddingVector.of(values);
  }

  private static EntityEmbedding embedding(EmbeddingVector vector, String model) {
    return new EntityEmbedding(vector, model, HASH, false, NOW);
  }

  private CandidateProfileId profile(String model, float... head) {
    var id = new CandidateProfileId(UUID.randomUUID());
    repository.saveProfileEmbedding(id, true, embedding(vector(head), model));
    return id;
  }

  private JobPostingId job(String model, float... head) {
    var id = new JobPostingId(UUID.randomUUID());
    repository.saveJobEmbedding(id, true, embedding(vector(head), model));
    return id;
  }

  @Test
  void theCosineIsTheRealOneBetweenTheTwoVectors() {
    String model = "modelo-" + UUID.randomUUID();
    var profile = profile(model, 1f);
    assertThat(repository.cosine(profile, job(model, 1f)))
        .hasValueSatisfying(c -> assertThat(c).isCloseTo(1.0, TOL));
    assertThat(repository.cosine(profile, job(model, 0f, 1f)))
        .hasValueSatisfying(c -> assertThat(c).isCloseTo(0.0, TOL));
    assertThat(repository.cosine(profile, job(model, 1f, 1f)))
        .hasValueSatisfying(c -> assertThat(c).isCloseTo(Math.sqrt(0.5), TOL));
    assertThat(repository.cosine(profile, job(model, -1f)))
        .hasValueSatisfying(c -> assertThat(c).isCloseTo(-1.0, TOL));
  }

  // ADR-0018: vectores de modelos distintos no son comparables.
  @Test
  void vectorsOfDifferentModelsAreNeverCompared() {
    var profile = profile("modelo-a-" + UUID.randomUUID(), 1f);
    var job = job("modelo-b-" + UUID.randomUUID(), 1f);

    assertThat(repository.cosine(profile, job)).isEmpty();
    assertThat(repository.cosinesForJob(job, List.of(profile))).isEmpty();
    assertThat(repository.cosinesForProfile(profile, List.of(job))).isEmpty();
  }

  @Test
  void aSideWithoutAVectorHasNoSimilarity() {
    String model = "modelo-" + UUID.randomUUID();
    var noVector = new CandidateProfileId(UUID.randomUUID());
    repository.setProfileDiscoverable(noVector, true);
    var job = job(model, 1f);

    assertThat(repository.cosine(noVector, job)).isEmpty();
    assertThat(repository.cosine(new CandidateProfileId(UUID.randomUUID()), job)).isEmpty();
    assertThat(repository.cosine(profile(model, 1f), new JobPostingId(UUID.randomUUID())))
        .isEmpty();
  }

  @Test
  void theBatchVariantsReturnTheSameAsTheSingleOnesAndOmitWhatIsMissing() {
    String model = "modelo-" + UUID.randomUUID();
    var job = job(model, 1f);
    var near = profile(model, 1f, 1f);
    var far = profile(model, 0f, 1f);
    var missing = new CandidateProfileId(UUID.randomUUID());

    var forJob = repository.cosinesForJob(job, List.of(near, far, missing));

    assertThat(forJob).containsOnlyKeys(near, far);
    assertThat(forJob.get(near)).isCloseTo(repository.cosine(near, job).orElseThrow(), TOL);
    assertThat(forJob.get(far)).isCloseTo(0.0, TOL);

    var otherJob = job(model, 0f, 1f);
    var forProfile =
        repository.cosinesForProfile(
            near, List.of(job, otherJob, new JobPostingId(UUID.randomUUID())));
    assertThat(forProfile).containsOnlyKeys(job, otherJob);
    assertThat(forProfile.get(job)).isCloseTo(forJob.get(near), TOL);
  }

  @Test
  void anEmptyRequestAnswersNothing() {
    assertThat(repository.cosinesForJob(new JobPostingId(UUID.randomUUID()), List.of())).isEmpty();
    assertThat(repository.cosinesForProfile(new CandidateProfileId(UUID.randomUUID()), List.of()))
        .isEmpty();
    assertThat(repository.accountsOf(List.of())).isEmpty();
  }

  // ---- directorio ----

  @Test
  void theDirectoryLinksProfilesToAccountsAndHidesAnonymizedOnes() {
    var account = new AccountId(UUID.randomUUID());
    var profile = new CandidateProfileId(UUID.randomUUID());
    repository.registerProfile(account, profile);
    var gone = new AccountId(UUID.randomUUID());
    var goneProfile = new CandidateProfileId(UUID.randomUUID());
    repository.registerProfile(gone, goneProfile);
    repository.anonymizeProfile(gone);

    assertThat(repository.accountOf(profile)).contains(account);
    assertThat(repository.profileOf(account)).contains(profile);
    assertThat(repository.accountsOf(List.of(profile, goneProfile)))
        .containsOnlyKeys(profile)
        .containsEntry(profile, account);
    assertThat(repository.accountOf(goneProfile)).isEmpty();
    assertThat(repository.profileOf(new AccountId(UUID.randomUUID()))).isEmpty();
  }

  // ---- «actualizando…» (BR-MATCH-03) ----

  @Test
  void aQueuedChangeIsPendingUntilItIsCompleted() {
    var key = UUID.randomUUID();

    assertThat(queue.isPending(EmbeddingEntity.PROFILE, key)).isFalse();
    queue.request(EmbeddingEntity.PROFILE, key, NOW);
    assertThat(queue.isPending(EmbeddingEntity.PROFILE, key)).isTrue();
    assertThat(queue.isPending(EmbeddingEntity.JOB, key)).isFalse();

    queue.discard(EmbeddingEntity.PROFILE, key);
    assertThat(queue.isPending(EmbeddingEntity.PROFILE, key)).isFalse();
  }
}
