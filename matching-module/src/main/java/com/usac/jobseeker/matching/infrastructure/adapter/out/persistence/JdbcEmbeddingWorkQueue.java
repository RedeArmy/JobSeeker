package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingEntity;
import com.usac.jobseeker.matching.application.usecase.embedentity.EmbeddingWorkQueuePort;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `matching_work_queue`. Misma técnica que la bandeja (ADR-0016, enmienda del
 * 24-nov-2026): el reclamo es una sentencia corta que arrienda con `FOR UPDATE SKIP LOCKED`, el
 * trabajo corre sin transacción abierta y cada resultado es una sentencia propia.
 */
public class JdbcEmbeddingWorkQueue implements EmbeddingWorkQueuePort {

  private static final String TYPE = "type";
  private static final String KEY = "key";
  private static final String ID = "id";

  private final JdbcClient jdbc;

  public JdbcEmbeddingWorkQueue(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // La fila es única por entidad (tipo y clave): pedir otra vez no la duplica, sube su generación
  // (para que quien
  // la
  // está ejecutando no la dé por terminada) y conserva el inicio más temprano, de modo que una
  // ráfaga de ediciones no aplaza el cálculo indefinidamente. Si estaba FAILED, una edición nueva
  // la reactiva: puede ser justo lo que la arregla.
  @Override
  public void request(EmbeddingEntity entity, UUID key, Instant notBefore) {
    jdbc.sql(
            "insert into matching_work_queue (id, entity_type, entity_key, not_before)"
                + " values (:id, :type, :key, :notBefore)"
                + " on conflict (entity_type, entity_key) do update set"
                + " generation = matching_work_queue.generation + 1,"
                + " not_before = least(matching_work_queue.not_before, excluded.not_before),"
                + " attempts = case when matching_work_queue.status = 'FAILED' then 0"
                + " else matching_work_queue.attempts end,"
                + " pending_since = case when matching_work_queue.status = 'FAILED' then now()"
                + " else matching_work_queue.pending_since end, status = 'PENDING'")
        .param(ID, UUID.randomUUID())
        .param(TYPE, entity.name())
        .param(KEY, key)
        .param("notBefore", at(notBefore))
        .update();
  }

  @Override
  public List<Job> claim(int limit, Duration lease, int maxAttempts) {
    // Un trabajo que murió con el proceso nunca llega al camino de fallo: si su arrendamiento
    // venció
    // con los intentos agotados se da por fallido, en vez de reclamarse sin fin.
    jdbc.sql(
            "update matching_work_queue set status = 'FAILED', locked_until = null"
                + " where status = 'PENDING' and attempts >= :maxAttempts"
                + " and locked_until <= clock_timestamp()")
        .param("maxAttempts", maxAttempts)
        .update();
    return jdbc.sql(
            "update matching_work_queue q set locked_until = clock_timestamp()"
                + " + make_interval(secs => :leaseSeconds), attempts = q.attempts + 1"
                + " from (select id from matching_work_queue where status = 'PENDING'"
                + " and not_before <= clock_timestamp()"
                + " and (locked_until is null or locked_until <= clock_timestamp())"
                + " order by not_before limit :limit for update skip locked) c"
                + " where q.id = c.id returning q.id, q.entity_type, q.entity_key, q.generation,"
                + " q.attempts,"
                + " q.pending_since")
        .param("limit", limit)
        // pgjdbc no infiere el tipo de un `Duration`: se pasa en segundos.
        .param("leaseSeconds", lease.toMillis() / 1000.0)
        .query(
            (rows, n) ->
                new Job(
                    rows.getObject(ID, UUID.class),
                    EmbeddingEntity.valueOf(rows.getString("entity_type")),
                    rows.getObject("entity_key", UUID.class),
                    rows.getLong("generation"),
                    rows.getInt("attempts"),
                    rows.getObject("pending_since", OffsetDateTime.class).toInstant()))
        .list();
  }

  @Override
  public void complete(Job job) {
    int deleted =
        jdbc.sql("delete from matching_work_queue where id = :id and generation = :generation")
            .param(ID, job.id())
            .param("generation", job.generation())
            .update();
    if (deleted == 0) {
      // Llegó otra petición mientras corría: se libera para ejecutarla con el estado nuevo.
      // La petición nueva es la que queda sin atender: la frescura se mide desde ahora.
      jdbc.sql(
              "update matching_work_queue set locked_until = null, attempts = 0,"
                  + " pending_since = now() where id = :id")
          .param(ID, job.id())
          .update();
    }
  }

  @Override
  public void retryAt(Job job, Instant notBefore) {
    jdbc.sql(
            "update matching_work_queue set locked_until = null, not_before = :notBefore"
                + " where id = :id")
        .param(ID, job.id())
        .param("notBefore", at(notBefore))
        .update();
  }

  @Override
  public void fail(Job job) {
    jdbc.sql(
            "update matching_work_queue set status = 'FAILED', locked_until = null"
                + " where id = :id")
        .param(ID, job.id())
        .update();
  }

  @Override
  public void discard(EmbeddingEntity entity, UUID key) {
    jdbc.sql("delete from matching_work_queue where entity_type = :type and entity_key = :key")
        .param(TYPE, entity.name())
        .param(KEY, key)
        .update();
  }

  private static OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  // Pendiente = esperando ser procesado, incluida la ventana de agrupación: la interfaz dice
  // «actualizando…» desde que hay un cambio (BR-MATCH-03). Un trabajo FAILED necesita revisión.
  @Override
  public boolean isPending(EmbeddingEntity entity, UUID key) {
    return jdbc.sql(
            "select exists (select 1 from matching_work_queue where entity_type = :type"
                + " and entity_key = :key and status = 'PENDING')")
        .param("type", entity.name())
        .param("key", key)
        .query(Boolean.class)
        .single();
  }
}
