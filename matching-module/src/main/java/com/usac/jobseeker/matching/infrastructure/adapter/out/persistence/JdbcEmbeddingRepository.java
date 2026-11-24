package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingStorePort;
import com.usac.jobseeker.matching.application.usecase.embedentity.JobEmbeddingState;
import com.usac.jobseeker.matching.application.usecase.embedentity.ProfileEmbeddingState;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.CandidateRetrievalPort;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.NearestProfile;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.EmbeddingMeta;
import com.usac.jobseeker.matching.domain.model.EmbeddingVector;
import com.usac.jobseeker.matching.domain.model.EntityEmbedding;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `profile_embeddings` y `job_embeddings` (`data-model.md` §4.7.1). Los vectores
 * viajan como texto `[a,b,c]` y se convierten con `cast(... as vector)`: es el formato que pgvector
 * admite sin necesidad de un tipo propio del driver.
 */
public class JdbcEmbeddingRepository implements EmbeddingStorePort, CandidateRetrievalPort {

  /**
   * El parámetro de la copia (`discoverable` o `published`), el mismo nombre en todas las
   * consultas.
   */
  private static final String ACCOUNT = "account";

  private static final String FLAG = "flag";

  private final JdbcClient jdbc;

  public JdbcEmbeddingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ---- perfiles ----

  @Override
  public Optional<ProfileEmbeddingState> findProfileState(CandidateProfileId id) {
    return jdbc.sql(
            "select discoverable, embedding_model, embedding_content_hash, embedding_truncated,"
                + " embedding_computed_at from profile_embeddings where candidate_profile_id = :id")
        .param("id", id.value())
        .query(
            (rows, n) -> new ProfileEmbeddingState(rows.getBoolean("discoverable"), metaOf(rows)))
        .optional();
  }

  @Override
  public Optional<EntityEmbedding> findProfileEmbedding(CandidateProfileId id) {
    return jdbc.sql(
            "select embedding::text as vector, embedding_model, embedding_content_hash,"
                + " embedding_truncated, embedding_computed_at from profile_embeddings"
                + " where candidate_profile_id = :id and embedding is not null")
        .param("id", id.value())
        .query((rows, n) -> embeddingOf(rows))
        .optional();
  }

  // Una cuenta anonimizada tiene una lápida en `profile_embedding_accounts`: ninguna de estas dos
  // escrituras puede dejar nada de ella, ni siquiera la de un cálculo que ya estaba en curso cuando
  // llegó la anonimización (BR-DATA-05). La condición va en la propia sentencia, no en una lectura
  // previa, para que no haya una ventana entre comprobar y escribir.
  private static final String NOT_ANONYMIZED =
      " where not exists (select 1 from profile_embedding_accounts a"
          + " where a.candidate_profile_id = :id and a.anonymized_at is not null)";

  // En un conflicto solo se reemplaza el bloque del vector: la copia de `discoverable` se deja como
  // está, porque el evento de descubribilidad es posterior al snapshot que leyó quien calculó el
  // vector y revertirlo reabriría un perfil que su dueño ya ocultó.
  @Override
  public boolean saveProfileEmbedding(
      CandidateProfileId id, boolean discoverableIfNew, EntityEmbedding embedding) {
    return jdbc.sql(
                "insert into profile_embeddings (candidate_profile_id, discoverable, embedding,"
                    + " embedding_model, embedding_dim, embedding_content_hash, embedding_truncated,"
                    + " embedding_computed_at) select :id, :flag, cast(:vector as vector), :model,"
                    + " :dim, :hash, :truncated, :computedAt"
                    + NOT_ANONYMIZED
                    + " on conflict (candidate_profile_id) do update set"
                    + " embedding = excluded.embedding, embedding_model = excluded.embedding_model,"
                    + " embedding_dim = excluded.embedding_dim,"
                    + " embedding_content_hash = excluded.embedding_content_hash,"
                    + " embedding_truncated = excluded.embedding_truncated,"
                    + " embedding_computed_at = excluded.embedding_computed_at, updated_at = now()")
            .param("id", id.value())
            .param(FLAG, discoverableIfNew)
            .params(embeddingParams(embedding))
            .update()
        == 1;
  }

  @Override
  public boolean setProfileDiscoverable(CandidateProfileId id, boolean discoverable) {
    return jdbc.sql(
                "insert into profile_embeddings (candidate_profile_id, discoverable)"
                    + " select :id, :flag"
                    + NOT_ANONYMIZED
                    + " on conflict (candidate_profile_id)"
                    + " do update set discoverable = excluded.discoverable, updated_at = now()")
            .param("id", id.value())
            .param(FLAG, discoverable)
            .update()
        == 1;
  }

  // Si la cuenta ya tiene lápida solo se completa el perfil que faltaba, y se devuelve `false`.
  @Override
  public boolean registerProfile(AccountId accountId, CandidateProfileId id) {
    jdbc.sql(
            "insert into profile_embedding_accounts (account_id, candidate_profile_id)"
                + " values (:account, :id) on conflict (account_id) do update set"
                + " candidate_profile_id = coalesce(profile_embedding_accounts.candidate_profile_id,"
                + " excluded.candidate_profile_id)")
        .param(ACCOUNT, accountId.value())
        .param("id", id.value())
        .update();
    return jdbc.sql(
            "select anonymized_at is null from profile_embedding_accounts"
                + " where account_id = :account")
        .param(ACCOUNT, accountId.value())
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<CandidateProfileId> findProfileIdByAccount(AccountId accountId) {
    return jdbc.sql(
            "select candidate_profile_id from profile_embedding_accounts"
                + " where account_id = :account and anonymized_at is null"
                + " and candidate_profile_id is not null")
        .param(ACCOUNT, accountId.value())
        .query((rows, n) -> new CandidateProfileId(rows.getObject(1, UUID.class)))
        .optional();
  }

  // La lápida se pone aunque `matching` no conociera el perfil: un cálculo en curso que lo registre
  // después se encuentra la cuenta ya anonimizada y no escribe nada.
  @Override
  public boolean anonymizeProfile(AccountId accountId) {
    boolean newlyBuried =
        jdbc.sql(
                    "insert into profile_embedding_accounts (account_id, anonymized_at)"
                        + " values (:account, now()) on conflict (account_id) do update set"
                        + " anonymized_at = now()"
                        + " where profile_embedding_accounts.anonymized_at is null")
                .param(ACCOUNT, accountId.value())
                .update()
            == 1;
    boolean cleared =
        jdbc.sql(
                    "update profile_embeddings set discoverable = false, embedding = null,"
                        + " embedding_model = null, embedding_dim = null,"
                        + " embedding_content_hash = null, embedding_truncated = false,"
                        + " embedding_computed_at = null, updated_at = now()"
                        + " where candidate_profile_id = (select candidate_profile_id"
                        + " from profile_embedding_accounts where account_id = :account)"
                        + " and (embedding is not null or discoverable)")
                .param(ACCOUNT, accountId.value())
                .update()
            == 1;
    return newlyBuried || cleared;
  }

  @Override
  public boolean clearProfileEmbedding(CandidateProfileId id) {
    return jdbc.sql(
                "update profile_embeddings set discoverable = false, embedding = null,"
                    + " embedding_model = null, embedding_dim = null,"
                    + " embedding_content_hash = null, embedding_truncated = false,"
                    + " embedding_computed_at = null, updated_at = now()"
                    + " where candidate_profile_id = :id")
            .param("id", id.value())
            .update()
        == 1;
  }

  // ---- vacantes ----

  @Override
  public Optional<JobEmbeddingState> findJobState(JobPostingId id) {
    return jdbc.sql(
            "select published, embedding_model, embedding_content_hash, embedding_truncated,"
                + " embedding_computed_at from job_embeddings where job_posting_id = :id")
        .param("id", id.value())
        .query((rows, n) -> new JobEmbeddingState(rows.getBoolean("published"), metaOf(rows)))
        .optional();
  }

  @Override
  public Optional<EntityEmbedding> findJobEmbedding(JobPostingId id) {
    return jdbc.sql(
            "select embedding::text as vector, embedding_model, embedding_content_hash,"
                + " embedding_truncated, embedding_computed_at from job_embeddings"
                + " where job_posting_id = :id and embedding is not null")
        .param("id", id.value())
        .query((rows, n) -> embeddingOf(rows))
        .optional();
  }

  @Override
  public void saveJobEmbedding(JobPostingId id, boolean publishedIfNew, EntityEmbedding embedding) {
    jdbc.sql(
            "insert into job_embeddings (job_posting_id, published, embedding, embedding_model,"
                + " embedding_dim, embedding_content_hash, embedding_truncated,"
                + " embedding_computed_at) values (:id, :flag, cast(:vector as vector), :model,"
                + " :dim, :hash, :truncated, :computedAt)"
                + " on conflict (job_posting_id) do update set"
                + " embedding = excluded.embedding, embedding_model = excluded.embedding_model,"
                + " embedding_dim = excluded.embedding_dim,"
                + " embedding_content_hash = excluded.embedding_content_hash,"
                + " embedding_truncated = excluded.embedding_truncated,"
                + " embedding_computed_at = excluded.embedding_computed_at, updated_at = now()")
        .param("id", id.value())
        .param(FLAG, publishedIfNew)
        .params(embeddingParams(embedding))
        .update();
  }

  @Override
  public void setJobPublished(JobPostingId id, boolean published) {
    jdbc.sql(
            "insert into job_embeddings (job_posting_id, published) values (:id, :flag)"
                + " on conflict (job_posting_id)"
                + " do update set published = excluded.published, updated_at = now()")
        .param("id", id.value())
        .param(FLAG, published)
        .update();
  }

  // ---- recuperación (etapa 1 de los sugeridos) ----

  // `<=>` es la distancia coseno de pgvector; la similitud es `1 - distancia`. La condición
  // coincide
  // con la del índice parcial para que pueda usarse, y el filtro por modelo evita comparar vectores
  // de modelos distintos durante un cambio de modelo (data-model.md §5.1).
  @Override
  public List<NearestProfile> nearestDiscoverable(EmbeddingVector query, String model, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return jdbc.sql(
            "select candidate_profile_id, 1 - (embedding <=> cast(:query as vector)) as similarity"
                + " from profile_embeddings where discoverable and embedding is not null"
                + " and embedding_model = :model"
                + " order by embedding <=> cast(:query as vector) limit :limit")
        .param("query", toLiteral(query))
        .param("model", model)
        .param("limit", limit)
        .query(
            (rows, n) ->
                new NearestProfile(
                    new CandidateProfileId(rows.getObject("candidate_profile_id", UUID.class)),
                    rows.getDouble("similarity")))
        .list();
  }

  // ---- conversiones ----

  private static java.util.Map<String, Object> embeddingParams(EntityEmbedding embedding) {
    return java.util.Map.of(
        "vector", toLiteral(embedding.vector()),
        "model", embedding.model(),
        "dim", embedding.vector().dimensions(),
        "hash", embedding.contentHash(),
        "truncated", embedding.truncated(),
        "computedAt", OffsetDateTime.ofInstant(embedding.computedAt(), ZoneOffset.UTC));
  }

  private static Optional<EmbeddingMeta> metaOf(ResultSet rows) throws SQLException {
    String model = rows.getString("embedding_model");
    if (model == null) {
      return Optional.empty();
    }
    return Optional.of(
        new EmbeddingMeta(
            model,
            rows.getString("embedding_content_hash"),
            rows.getBoolean("embedding_truncated"),
            rows.getObject("embedding_computed_at", OffsetDateTime.class).toInstant()));
  }

  private static EntityEmbedding embeddingOf(ResultSet rows) throws SQLException {
    return new EntityEmbedding(
        fromLiteral(rows.getString("vector")),
        rows.getString("embedding_model"),
        rows.getString("embedding_content_hash"),
        rows.getBoolean("embedding_truncated"),
        rows.getObject("embedding_computed_at", OffsetDateTime.class).toInstant());
  }

  static String toLiteral(EmbeddingVector vector) {
    StringBuilder text = new StringBuilder("[");
    float[] values = vector.values();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        text.append(',');
      }
      text.append(values[i]);
    }
    return text.append(']').toString();
  }

  static EmbeddingVector fromLiteral(String literal) {
    String body = literal.substring(1, literal.length() - 1);
    String[] parts = body.split(",");
    float[] values = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      values[i] = Float.parseFloat(parts[i]);
    }
    return EmbeddingVector.of(values);
  }
}
