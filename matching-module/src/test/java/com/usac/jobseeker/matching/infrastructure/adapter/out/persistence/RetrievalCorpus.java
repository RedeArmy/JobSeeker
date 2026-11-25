package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Un corpus de perfiles con la geometría de embeddings reales y no uniforme: los vectores uniformes
 * aleatorios son el peor caso de los índices de vecino más próximo y dan un recall de 0,28 que no
 * dice nada del sistema (`anexos/embeddings-esquema/recall`). Aquí hay una dirección común
 * (anisotropía, coseno medio entre grupos ≈ 0,5 como en los vectores reales de `qwen3-embedding`),
 * grupos y ruido. Sigue siendo sintético: mide planes, latencia y el efecto de las reglas de
 * consulta, no la calidad de recuperación sobre perfiles reales.
 */
final class RetrievalCorpus {

  static final int DIMENSIONS = 1024;
  static final String MODEL = "qwen3-embedding:0.6b";
  static final String OTHER_MODEL = "otro-modelo:1b";
  private static final int CLUSTERS = 60;
  private static final String HASH = "c".repeat(64);

  private final Random random;
  private final float[] common;
  private final List<float[]> centres = new ArrayList<>();

  RetrievalCorpus(long seed) {
    this.random = new Random(seed);
    this.common = gaussian();
    for (int k = 0; k < CLUSTERS; k++) {
      float[] own = gaussian();
      float[] centre = new float[DIMENSIONS];
      for (int i = 0; i < DIMENSIONS; i++) {
        centre[i] = 0.6f * common[i] + 0.8f * own[i];
      }
      centres.add(normalize(centre));
    }
  }

  /** Un punto de un grupo al azar, con ruido de la amplitud dada. */
  float[] point(double noise) {
    float[] centre = centres.get(random.nextInt(CLUSTERS));
    float[] x = new float[DIMENSIONS];
    float[] n = gaussian();
    for (int i = 0; i < DIMENSIONS; i++) {
      x[i] = centre[i] + (float) (noise * n[i] / Math.sqrt(DIMENSIONS));
    }
    return normalize(x);
  }

  /** Una consulta con la misma distribución que el corpus (una vacante cerca de algún grupo). */
  EmbeddingVector query() {
    return EmbeddingVector.of(point(0.5));
  }

  /**
   * Inserta `count` perfiles; `otherModelShare` de ellos son de otro modelo (un cambio de modelo en
   * curso) y `hiddenShare` no son descubribles.
   */
  void load(DataSource dataSource, int count, double otherModelShare, double hiddenShare) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("delete from profile_embeddings");
    String sql =
        "insert into profile_embeddings (candidate_profile_id, discoverable, embedding,"
            + " embedding_model, embedding_dim, embedding_content_hash, embedding_truncated,"
            + " embedding_computed_at) values (?, ?, cast(? as vector), ?, ?, ?, false, ?)";
    List<Object[]> batch = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      batch.add(
          new Object[] {
            UUID.randomUUID(),
            random.nextDouble() >= hiddenShare,
            literal(point(0.6)),
            random.nextDouble() < otherModelShare ? OTHER_MODEL : MODEL,
            DIMENSIONS,
            HASH,
            new Timestamp(System.currentTimeMillis())
          });
      if (batch.size() == 500) {
        jdbc.batchUpdate(sql, batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      jdbc.batchUpdate(sql, batch);
    }
    jdbc.execute("analyze profile_embeddings");
  }

  static String literal(float[] values) {
    StringBuilder text = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      text.append(i == 0 ? "" : ",").append(values[i]);
    }
    return text.append(']').toString();
  }

  private float[] gaussian() {
    float[] x = new float[DIMENSIONS];
    for (int i = 0; i < DIMENSIONS; i++) {
      x[i] = (float) random.nextGaussian();
    }
    return x;
  }

  private static float[] normalize(float[] x) {
    double norm = 0;
    for (float v : x) {
      norm += (double) v * v;
    }
    float scale = (float) (1 / Math.sqrt(norm));
    float[] out = new float[x.length];
    for (int i = 0; i < x.length; i++) {
      out[i] = x[i] * scale;
    }
    return out;
  }
}
