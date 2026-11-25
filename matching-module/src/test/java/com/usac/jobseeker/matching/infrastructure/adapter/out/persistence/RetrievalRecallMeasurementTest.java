package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.NearestProfile;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.infrastructure.MatchingPostgres;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Medición de TD-012 (recall y latencia de la recuperación vectorial), **no** es una prueba de
 * regresión: no corre salvo con `-Dretrieval.measure=true`. Imprime una tabla Markdown para el
 * informe `11-medicion-recuperacion-vectorial.md`.
 *
 * <p>Uso: `mvn -pl matching-module test -Dtest=RetrievalRecallMeasurementTest
 * -Dretrieval.measure=true -Dretrieval.sizes=10000,30000`. El corpus es sintético con geometría de
 * embeddings reales (ver {@link RetrievalCorpus}): mide planes, latencia y el efecto de las reglas
 * de consulta, no la calidad sobre perfiles reales.
 */
@EnabledIfSystemProperty(named = "retrieval.measure", matches = "true")
class RetrievalRecallMeasurementTest {

  private static final int QUERIES = 30;
  private static final int[] PAGES = {10, 20, 50};
  private static final int[] EF = {40, 100, 200};

  private final DataSource dataSource = MatchingPostgres.dataSource();
  private final JdbcClient jdbc = JdbcClient.create(dataSource);
  private final TransactionTemplate tx =
      new TransactionTemplate(new DataSourceTransactionManager(dataSource));

  @Test
  void measure() {
    for (String size : System.getProperty("retrieval.sizes", "10000").split(",")) {
      int count = Integer.parseInt(size.trim());
      RetrievalCorpus corpus = new RetrievalCorpus(7);
      long start = System.nanoTime();
      double otherShare = Double.parseDouble(System.getProperty("retrieval.otherShare", "0.3"));
      corpus.load(dataSource, count, otherShare, 0.2);
      report(
          "\n## %d perfiles (%.0f %% de otro modelo, 20 %% ocultos); carga %d s%n",
          count, otherShare * 100, (System.nanoTime() - start) / 1_000_000_000L);
      List<EmbeddingVector> queries = new ArrayList<>();
      for (int i = 0; i < QUERIES; i++) {
        queries.add(corpus.query());
      }
      report("Plan por defecto del planificador: %s%n", defaultPlan(queries.get(0)));
      report(
          "%n| Página | ef_search | Regla | Recall medio | Filas medias | p50 ms | p95 ms |%n|---|---|---|---|---|---|---|%n");
      for (int page : PAGES) {
        List<Set<UUID>> exact = new ArrayList<>();
        for (EmbeddingVector q : queries) {
          exact.add(exact(q, page));
        }
        for (int ef : EF) {
          // Con la regla la página siempre sale completa: es lo que esta medición debe seguir
          // mostrando.
          assertThat(row(page, ef, true, queries, exact)).isCloseTo(page, within(0.001));
          row(page, ef, false, queries, exact);
        }
      }
    }
  }

  /** Imprime la fila y devuelve la media de filas devueltas por consulta. */
  private double row(
      int page, int ef, boolean rule, List<EmbeddingVector> queries, List<Set<UUID>> exact) {
    var repository = new JdbcEmbeddingRepository(jdbc, ef);
    List<Double> times = new ArrayList<>();
    double recall = 0;
    double rows = 0;
    for (int i = 0; i < queries.size(); i++) {
      EmbeddingVector query = queries.get(i);
      long t0 = System.nanoTime();
      Set<UUID> found =
          forcedIndex(
              () ->
                  rule
                      ? ids(repository.nearestDiscoverable(query, RetrievalCorpus.MODEL, page))
                      : plain(query, page, ef));
      times.add((System.nanoTime() - t0) / 1e6);
      Set<UUID> hit = new HashSet<>(found);
      hit.retainAll(exact.get(i));
      recall += (double) hit.size() / exact.get(i).size();
      rows += found.size();
    }
    times.sort(Comparator.naturalOrder());
    report(
        "| %d | %d | %s | %.3f | %.1f | %.1f | %.1f |%n",
        page,
        ef,
        rule ? "iterative_scan + ef>=página" : "sin regla",
        recall / queries.size(),
        rows / queries.size(),
        times.get(times.size() / 2),
        times.get((int) Math.ceil(times.size() * 0.95) - 1));
    return rows / queries.size();
  }

  /** La consulta sin la regla: solo `ef_search`, como la dejaría pgvector por defecto. */
  private Set<UUID> plain(EmbeddingVector query, int page, int ef) {
    jdbc.sql("select set_config('hnsw.ef_search', :ef, true)")
        .param("ef", String.valueOf(ef))
        .query()
        .singleRow();
    return new HashSet<>(
        jdbc.sql(
                "select candidate_profile_id from profile_embeddings where discoverable and embedding is not null"
                    + " and embedding_model = :model order by embedding <=> cast(:q as vector) limit :limit")
            .param("model", RetrievalCorpus.MODEL)
            .param("q", RetrievalCorpus.literal(query.values()))
            .param("limit", page)
            .query(UUID.class)
            .list());
  }

  private Set<UUID> exact(EmbeddingVector query, int page) {
    return tx.execute(
        status -> {
          jdbc.sql("set local enable_indexscan = off").update();
          jdbc.sql("set local enable_bitmapscan = off").update();
          return new HashSet<>(
              jdbc.sql(
                      "select candidate_profile_id from profile_embeddings where discoverable and embedding is not null"
                          + " and embedding_model = :model order by embedding <=> cast(:q as vector) limit :limit")
                  .param("model", RetrievalCorpus.MODEL)
                  .param("q", RetrievalCorpus.literal(query.values()))
                  .param("limit", page)
                  .query(UUID.class)
                  .list());
        });
  }

  private <T> T forcedIndex(java.util.function.Supplier<T> action) {
    return tx.execute(
        status -> {
          jdbc.sql("drop index idx_pe_embedding_model").update();
          jdbc.sql("set local enable_seqscan = off").update();
          jdbc.sql("set local enable_bitmapscan = off").update();
          T result = action.get();
          status.setRollbackOnly();
          return result;
        });
  }

  private String defaultPlan(EmbeddingVector query) {
    List<String> plan =
        jdbc.sql(
                "explain select candidate_profile_id from profile_embeddings where discoverable and embedding is not null"
                    + " and embedding_model = :model order by embedding <=> cast(:q as vector) limit 50")
            .param("model", RetrievalCorpus.MODEL)
            .param("q", RetrievalCorpus.literal(query.values()))
            .query(String.class)
            .list();
    return plan.stream().anyMatch(l -> l.contains("idx_pe_embedding_hnsw"))
        ? "usa el índice HNSW"
        : "escaneo exacto (no usa el HNSW)";
  }

  private static Set<UUID> ids(List<NearestProfile> found) {
    Set<UUID> ids = new HashSet<>();
    found.forEach(n -> ids.add(n.candidate().value()));
    return ids;
  }

  private static void report(String format, Object... args) {
    System.out.printf(format, args);
    System.out.flush();
  }
}
