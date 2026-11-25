package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.NearestProfile;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TD-012: la consulta de recuperación con su filtro adicional por modelo, forzando el índice HNSW
 * (a pocos miles de filas el planificador prefiere el escaneo exacto y no se vería el problema) y
 * comparándola con el resultado exacto. Prueba que las reglas de consulta —escaneo iterativo y
 * `ef_search` no menor que lo pedido— devuelven la página completa y casi igual a la exacta, y que
 * sin ellas la página sale incompleta.
 */
class JdbcEmbeddingRetrievalRecallTest {

  private static final int PROFILES = 4000;
  private static final int PAGE = 50;
  private static final int QUERIES = 12;

  private static final DataSource DATA_SOURCE = MatchingPostgres.dataSource();
  private static final JdbcClient JDBC = JdbcClient.create(DATA_SOURCE);
  private static final TransactionTemplate TX =
      new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
  private static RetrievalCorpus corpus;

  @BeforeAll
  static void load() {
    corpus = new RetrievalCorpus(42);
    // Un 30 % de otro modelo y un 20 % oculto: el filtro por modelo no está en el índice.
    corpus.load(DATA_SOURCE, PROFILES, 0.3, 0.2);
  }

  @AfterAll
  static void clean() {
    JDBC.sql("delete from profile_embeddings").update();
  }

  /**
   * Fuerza el índice HNSW: a pocos miles de filas el planificador prefiere el btree de
   * `embedding_model` más una ordenación (exacta), así que se quita ese índice dentro de la
   * transacción, que se revierte al terminar, y se comprueba por `EXPLAIN` que de verdad se usa el
   * HNSW. Sin esa comprobación la prueba pasaría sin ejercitar el índice.
   */
  private static <T> T withIndexForced(java.util.function.Supplier<T> action) {
    return TX.execute(
        status -> {
          JDBC.sql("drop index idx_pe_embedding_model").update();
          JDBC.sql("set local enable_seqscan = off").update();
          JDBC.sql("set local enable_bitmapscan = off").update();
          assertThat(plan()).contains("idx_pe_embedding_hnsw");
          T result = action.get();
          status.setRollbackOnly();
          return result;
        });
  }

  private static String plan() {
    return String.join(
        "\n",
        JDBC.sql(
                "explain select candidate_profile_id from profile_embeddings where discoverable"
                    + " and embedding is not null and embedding_model = :model"
                    + " order by embedding <=> cast(:q as vector) limit 50")
            .param("model", RetrievalCorpus.MODEL)
            .param("q", RetrievalCorpus.literal(corpus.query().values()))
            .query(String.class)
            .list());
  }

  private static Set<UUID> ids(List<NearestProfile> found) {
    Set<UUID> ids = new HashSet<>();
    found.forEach(n -> ids.add(n.candidate().value()));
    return ids;
  }

  /** Lo mismo que la consulta real, exacto: sin índice y sin ningún recorte. */
  private static Set<UUID> exact(EmbeddingVector query, int limit) {
    return TX.execute(
        status -> {
          JDBC.sql("set local enable_indexscan = off").update();
          JDBC.sql("set local enable_bitmapscan = off").update();
          Set<UUID> found =
              new HashSet<>(
                  JDBC.sql(
                          "select candidate_profile_id from profile_embeddings where discoverable"
                              + " and embedding is not null and embedding_model = :model"
                              + " order by embedding <=> cast(:q as vector) limit :limit")
                      .param("model", RetrievalCorpus.MODEL)
                      .param("q", RetrievalCorpus.literal(query.values()))
                      .param("limit", limit)
                      .query(UUID.class)
                      .list());
          return found;
        });
  }

  private static double recall(Set<UUID> found, Set<UUID> exact) {
    Set<UUID> hit = new HashSet<>(found);
    hit.retainAll(exact);
    return exact.isEmpty() ? 1 : (double) hit.size() / exact.size();
  }

  @Test
  void aFullPageComesBackAlmostEqualToTheExactOneEvenWithTheModelFilterOutsideTheIndex() {
    var repository = new JdbcEmbeddingRepository(JDBC);
    double total = 0;
    for (int q = 0; q < QUERIES; q++) {
      EmbeddingVector query = corpus.query();

      List<NearestProfile> found =
          withIndexForced(() -> repository.nearestDiscoverable(query, RetrievalCorpus.MODEL, PAGE));

      assertThat(found).as("la página completa, no menos filas que `limit`").hasSize(PAGE);
      total += recall(ids(found), exact(query, PAGE));
    }

    assertThat(total / QUERIES).as("recall@50 medio frente al exacto").isGreaterThanOrEqualTo(0.95);
  }

  @Test
  void theResultsAreOrderedByDescendingSimilarity() {
    var repository = new JdbcEmbeddingRepository(JDBC);

    List<NearestProfile> found =
        withIndexForced(
            () -> repository.nearestDiscoverable(corpus.query(), RetrievalCorpus.MODEL, PAGE));

    assertThat(found)
        .extracting(NearestProfile::cosineSimilarity)
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  // Control negativo: el mismo índice con el ajuste por defecto de pgvector y sin escaneo iterativo
  // devuelve la página incompleta, que es lo que la regla evita.
  @Test
  void withoutTheRulesTheSamePageComesBackIncomplete() {
    EmbeddingVector query = corpus.query();

    int rows =
        withIndexForced(
            () ->
                JDBC.sql(
                        "select candidate_profile_id from profile_embeddings where discoverable"
                            + " and embedding is not null and embedding_model = :model"
                            + " order by embedding <=> cast(:q as vector) limit :limit")
                    .param("model", RetrievalCorpus.MODEL)
                    .param("q", RetrievalCorpus.literal(query.values()))
                    .param("limit", PAGE)
                    .query(UUID.class)
                    .list()
                    .size());

    assertThat(rows).isLessThan(PAGE);
  }

  @Test
  void theSettingsStayInsideTheTransactionAndDoNotLeakToThePool() throws Exception {
    // Una sola conexión: con una nueva por consulta no se podría ver si el ajuste se «pega».
    try (var single =
        new org.springframework.jdbc.datasource.SingleConnectionDataSource(
            ((org.springframework.jdbc.datasource.DriverManagerDataSource) DATA_SOURCE).getUrl(),
            "test",
            "test",
            true)) {
      var jdbc = JdbcClient.create(single);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(single));
      var repository = new JdbcEmbeddingRepository(jdbc, 200);

      tx.executeWithoutResult(
          status -> {
            repository.nearestDiscoverable(corpus.query(), RetrievalCorpus.MODEL, 10);
            assertThat(setting(jdbc, "hnsw.ef_search")).isEqualTo("200");
            assertThat(setting(jdbc, "hnsw.iterative_scan")).isEqualTo("relaxed_order");
          });

      // Fuera de la transacción vuelve el valor por defecto de pgvector.
      assertThat(setting(jdbc, "hnsw.ef_search")).isEqualTo("40");
      assertThat(setting(jdbc, "hnsw.iterative_scan")).isEqualTo("off");
    }
  }

  @Test
  void efSearchIsNeverBelowThePageSize() {
    var repository = new JdbcEmbeddingRepository(JDBC, 20);

    TX.executeWithoutResult(
        status -> {
          repository.nearestDiscoverable(corpus.query(), RetrievalCorpus.MODEL, 50);
          assertThat(setting(JDBC, "hnsw.ef_search")).isEqualTo("50");
        });
  }

  private static String setting(JdbcClient jdbc, String name) {
    return jdbc.sql("select current_setting(:name)")
        .param("name", name)
        .query(String.class)
        .single();
  }
}
