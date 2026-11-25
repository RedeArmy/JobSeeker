package com.usac.jobseeker.matching.infrastructure.adapter.out.persistence;

import com.usac.jobseeker.common.domain.model.AccountId;
import com.usac.jobseeker.matching.application.usecase.suggestcandidatesforjob.ProfileViewLogPort;
import com.usac.jobseeker.matching.domain.model.CandidateProfileId;
import com.usac.jobseeker.matching.domain.model.JobPostingId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Adaptador JDBC de `profile_view_log` (`data-model.md` §4.7.2). Dos decisiones:
 *
 * <ul>
 *   <li>La cuota se cuenta **en la base de datos y no en Redis** (el LLD proponía un contador): la
 *       fuente es una sola, es atómica con el registro de lo mostrado y no hay un camino en que
 *       Redis caído deje pasar o bloquee sin que nadie lo decida.
 *   <li>El candado es de transacción y por reclutador, como el de la puerta de publicar: dos
 *       pedidos del mismo reclutador se ponen en fila y el segundo ve lo que registró el primero.
 * </ul>
 */
public class JdbcProfileViewLog implements ProfileViewLogPort {

  private static final String CONTEXT = "SUGGESTION";

  private final JdbcClient jdbc;

  public JdbcProfileViewLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lock(AccountId viewer) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", "profile_views:" + viewer)
        .query()
        .singleRow();
  }

  @Override
  public Set<CandidateProfileId> viewedSince(AccountId viewer, Instant since) {
    return new HashSet<>(
        jdbc.sql(
                "select distinct candidate_profile_id from profile_view_log"
                    + " where viewer_account_id = :viewer and viewed_at > :since")
            .param("viewer", viewer.value())
            .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
            .query((rows, n) -> new CandidateProfileId(rows.getObject(1, UUID.class)))
            .list());
  }

  @Override
  public void append(
      AccountId viewer, JobPostingId job, List<CandidateProfileId> shown, Instant at) {
    for (CandidateProfileId candidate : shown) {
      jdbc.sql(
              "insert into profile_view_log (id, viewer_account_id, candidate_profile_id,"
                  + " job_posting_id, context, viewed_at)"
                  + " values (:id, :viewer, :candidate, :job, :context, :at)")
          .param("id", UUID.randomUUID())
          .param("viewer", viewer.value())
          .param("candidate", candidate.value())
          .param("job", job.value())
          .param("context", CONTEXT)
          .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
          .update();
    }
  }
}
